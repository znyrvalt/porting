#!/usr/bin/env python3
"""Build the AltarSMP Bedrock/Geyser crossplay package from the source archives.

Run with no arguments from anywhere in the repository:

    python3 tools/crossplay/build_custom_content.py

Everything under ``AltarSMP-Custom-Content/`` is regenerated from
``AltarSMP-ResourcePack.zip`` and ``AltarSMP.zip`` plus the mod's own content
tables.  The build is deterministic: zip entries are sorted, timestamps are
pinned to 1980-02-01 00:00:00, the compression level is fixed and every JSON
document is written with a stable indent and key order, so two clean runs
produce byte-identical output.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
import re
import shutil
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import discovery  # noqa: E402
import geometry as geo  # noqa: E402
import modside  # noqa: E402
import packsource  # noqa: E402
import pngcodec  # noqa: E402
import render  # noqa: E402

OUT_DIR = "AltarSMP-Custom-Content"
NAMESPACE = "altarsmp"
ZIP_DATE = (1980, 2, 1, 0, 0, 0)
UUID_SEED = "altarsmp-bedrock-crossplay-2.0.5"

# Bedrock cannot express these Java model-dispatch states; each one is recorded
# with the reason rather than silently dropped.
STATE_NOTES = {
    "using_item": "held-use state; approximated by the charge animation, not a second model",
    "!using_item": "default (not using) state; this is the model that ships",
    "display_context": "per-view model swap; Bedrock has one held model per attachable",
    "charge_type": "crossbow charge type; Bedrock uses the vanilla charged geometry",
    "crossbow/pull": "crossbow pull stage; mapped to the charge animation",
    "use_duration": "bow pull stage; mapped to the charge animation",
    "use_cycle": "animated use cycle; not expressible in an attachable",
}


# --------------------------------------------------------------------- helpers

def jdump(obj) -> bytes:
    return (json.dumps(obj, indent=2, sort_keys=False, ensure_ascii=False) + "\n").encode("utf-8")


def slugify(text: str) -> str:
    text = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")
    return re.sub(r"_+", "_", text)


def stable_uuid(*parts) -> str:
    digest = hashlib.sha256((UUID_SEED + "|" + "|".join(str(p) for p in parts)).encode()).digest()
    b = bytearray(digest[:16])
    b[6] = (b[6] & 0x0F) | 0x40
    b[8] = (b[8] & 0x3F) | 0x80
    hexed = b.hex()
    return "%s-%s-%s-%s-%s" % (hexed[:8], hexed[8:12], hexed[12:16], hexed[16:20], hexed[20:])


MINI = re.compile(r"<(/?)([a-z_#0-9:]+)>")
COLOURS = {
    "black": "0", "dark_blue": "1", "dark_green": "2", "dark_aqua": "3", "dark_red": "4",
    "dark_purple": "5", "gold": "6", "gray": "7", "grey": "7", "dark_gray": "8", "dark_grey": "8",
    "blue": "9", "green": "a", "aqua": "b", "red": "c", "light_purple": "d", "yellow": "e",
    "white": "f", "bold": "l", "italic": "o", "underlined": "n", "strikethrough": "m",
    "obfuscated": "k", "reset": "r",
}


def minimessage_to_sections(text: str) -> str:
    """MiniMessage markup -> Bedrock section codes (gradients take their first colour)."""
    out = []
    pos = 0
    for match in re.finditer(r"<([^<>]+)>", text):
        out.append(text[pos:match.start()])
        pos = match.end()
        tag = match.group(1)
        if tag.startswith("/"):
            out.append("\u00a7r")
            continue
        head = tag.split(":")[0]
        if head in ("gradient", "color", "colour", "c"):
            parts = tag.split(":")[1:]
            code = None
            for part in parts:
                if part in COLOURS:
                    code = COLOURS[part]
                    break
                if part.startswith("#"):
                    code = _nearest_colour(part)
                    break
            out.append("\u00a7" + (code or "f"))
        elif head in COLOURS:
            out.append("\u00a7" + COLOURS[head])
        elif head.startswith("#"):
            out.append("\u00a7" + _nearest_colour(head))
    out.append(text[pos:])
    return "".join(out)


PALETTE = {
    "0": (0, 0, 0), "1": (0, 0, 170), "2": (0, 170, 0), "3": (0, 170, 170), "4": (170, 0, 0),
    "5": (170, 0, 170), "6": (255, 170, 0), "7": (170, 170, 170), "8": (85, 85, 85),
    "9": (85, 85, 255), "a": (85, 255, 85), "b": (85, 255, 255), "c": (255, 85, 85),
    "d": (255, 85, 255), "e": (255, 255, 85), "f": (255, 255, 255),
}


def _nearest_colour(hexcode: str) -> str:
    try:
        value = int(hexcode.lstrip("#"), 16)
    except ValueError:
        return "f"
    rgb = ((value >> 16) & 255, (value >> 8) & 255, value & 255)
    best, best_d = "f", None
    for code, ref in PALETTE.items():
        d = sum((rgb[i] - ref[i]) ** 2 for i in range(3))
        if best_d is None or d < best_d:
            best, best_d = code, d
    return best


# ------------------------------------------------------------------- the build

class Build:
    def __init__(self, root):
        self.root = root
        self.source = packsource.PackSource(root)
        self.out = os.path.join(root, OUT_DIR)
        self.pack = {}          # zip path -> bytes (the Bedrock pack contents)
        self.mappings = {}      # "minecraft:base" -> [entry, ...]
        self.missing = []       # (what, reason)
        self.icon_rows = []     # (identifier, base, cmd, icon_source, model)
        self.lang = {}          # bedrock text key -> name
        self.states = []        # (identifier, state, note)
        self.texture_cache = {}

    # ------------------------------------------------------------ asset access

    def texture(self, ref):
        if ref in self.texture_cache:
            return self.texture_cache[ref]
        path = self.source.texture_path(ref)
        image = None
        if path:
            try:
                image = pngcodec.decode(self.source.read(path))
            except pngcodec.PngError:
                image = None
        self.texture_cache[ref] = image
        return image

    def animation_meta(self, ref):
        path = self.source.texture_path(ref)
        if not path:
            return None
        meta = path + ".mcmeta"
        if not self.source.exists(meta):
            return None
        try:
            data = self.source.read_json(meta)
        except ValueError:
            return None
        return (data or {}).get("animation")

    # --------------------------------------------------------------- main pass

    def run(self):
        pairs = discovery.discover_pairs(self.source)
        mod_pairs, unknown = modside.mod_pairs(self.root)
        by_key = {(p.base, p.cmd): p for p in pairs}

        used_names = set()
        for pair in pairs:
            self.emit_pair(pair, used_names)

        # every mod-side pair must be mapped or explained
        mapped = {(base.split(":", 1)[1], entry["custom_model_data"])
                  for base, entries in self.mappings.items() for entry in entries}
        for pair in mod_pairs:
            if (pair.base, pair.cmd) in mapped:
                continue
            if pair.cmd == 0:
                self.missing.append(("%s cmd %d" % (pair.base, pair.cmd),
                                     "model data 0 means 'no custom model' - the vanilla item is shown; "
                                     "sources: %s" % ", ".join(sorted(set(pair.sources))[:3])))
                continue
            self.missing.append(("%s cmd %d" % (pair.base, pair.cmd),
                                 "the mod can produce this pair but neither source pack defines a model "
                                 "for it, so there is no artwork to convert; sources: %s"
                                 % ", ".join(sorted(set(pair.sources))[:3])))
        for material, cmd, origin in unknown:
            self.missing.append(("%s cmd %s" % (material or "unknown base", cmd),
                                 "model data is computed at runtime at %s; the concrete values it can "
                                 "take are mapped individually" % origin))

        self.emit_block_skeletons()
        self.emit_equipment()
        self.emit_sounds()
        self.emit_texts()
        self.emit_shell()
        return by_key, pairs, mod_pairs

    # ------------------------------------------------------------------- pairs

    def emit_pair(self, pair, used_names):
        leaf = pair.primary
        if leaf is None:
            return
        model = discovery.resolve_model(self.source, leaf.model)
        if model is None:
            self.missing.append(("%s cmd %d -> %s" % (pair.base, pair.cmd, leaf.model),
                                 "the item definition points at a model file that is not present in "
                                 "either source pack"))
            return

        name = slugify(leaf.model.split(":")[-1].split("/")[-1])
        if not name:
            name = "%s_%d" % (pair.base, pair.cmd)
        identifier = "%s:%s" % (NAMESPACE, name)
        if identifier in used_names:
            identifier = "%s:%s_%d" % (NAMESPACE, name, pair.cmd)
        used_names.add(identifier)
        short = identifier.split(":", 1)[1]

        textures = {}
        for ref in discovery.model_textures(model):
            image = self.texture(ref)
            if image is not None:
                textures[ref] = image
        if not textures:
            self.missing.append(("%s cmd %d -> %s" % (pair.base, pair.cmd, leaf.model),
                                 "the model resolves but none of its textures exist in either pack"))
            return

        icon_leaf = pair.icon_leaf
        icon_model = model
        icon_textures = textures
        if icon_leaf is not leaf:
            alt = discovery.resolve_model(self.source, icon_leaf.model)
            if alt is not None:
                alt_textures = {}
                for ref in discovery.model_textures(alt):
                    image = self.texture(ref)
                    if image is not None:
                        alt_textures[ref] = image
                if alt_textures:
                    icon_model, icon_textures = alt, alt_textures
        icon, icon_source = render.render_icon(icon_model, icon_textures)
        if icon is None:
            self.missing.append(("%s cmd %d -> %s" % (pair.base, pair.cmd, leaf.model),
                                 "the model could not be rasterised into an inventory icon"))
            return
        self.pack["textures/items/%s/%s.png" % (NAMESPACE, short)] = pngcodec.encode(icon)
        self.icon_rows.append((identifier, pair.base, pair.cmd, icon_source, leaf.model))

        entry = {
            "name": identifier,
            "custom_model_data": pair.cmd,
            "display_name": short.replace("_", " ").title(),
            "bedrock_options": {"icon": short},
        }

        primary_ref = discovery.model_textures(model)[0]
        animation = self.animation_meta(primary_ref)
        if animation:
            self.emit_flipbook(short, primary_ref, textures[primary_ref], animation)

        if model.get("elements"):
            self.emit_attachable(identifier, short, model, textures, leaf)
            entry["bedrock_options"]["allow_offhand"] = True

        for extra in pair.extra_states:
            for state in extra.states:
                head = state.split("=")[0].lstrip("!")
                note = STATE_NOTES.get(head) or STATE_NOTES.get(state) or \
                    "state '%s' has no Bedrock equivalent; the default model is used" % state
                self.states.append((identifier, state, note))

        self.mappings.setdefault("minecraft:%s" % pair.base, []).append(entry)
        self.lang["item.%s" % identifier] = entry["display_name"]

    # ------------------------------------------------------------ 3D held item

    def emit_attachable(self, identifier, short, model, textures, leaf):
        refs = discovery.model_textures(model)
        primary = refs[0]
        image = textures[primary]
        cubes, width, height = geo.convert_elements(
            model, (image.width, image.height), model.get("texture_size"))
        if not cubes:
            return
        geo_id = "geometry.%s.%s" % (NAMESPACE, short)
        self.pack["models/entity/%s/%s.geo.json" % (NAMESPACE, short)] = jdump(
            geo.geometry(geo_id, cubes, width, height))
        self.pack["textures/entity/%s/%s.png" % (NAMESPACE, short)] = pngcodec.encode(image)

        wants_charge = any("pull" in s or "using_item" in s or "charge" in s or "use_duration" in s
                           for extra in [leaf] for s in extra.states) or True
        attachable = {
            "format_version": "1.10.0",
            "minecraft:attachable": {
                "description": {
                    "identifier": identifier,
                    "materials": {"default": "entity_alphatest", "enchanted": "entity_alphatest_glint"},
                    "textures": {
                        "default": "textures/entity/%s/%s" % (NAMESPACE, short),
                        "enchanted": "textures/misc/enchanted_item_glint",
                    },
                    "geometry": {"default": geo_id},
                    "animations": {
                        "hold": "animation.%s.hold" % NAMESPACE,
                        "charge": "animation.%s.charge" % NAMESPACE,
                    },
                    "scripts": {
                        "pre_animation": ["v.is_first_person = query.is_first_person;"],
                        "animate": ["hold", "charge"],
                    },
                    "render_controllers": ["controller.render.%s" % NAMESPACE],
                },
            },
        }
        self.pack["attachables/%s.json" % short] = jdump(attachable)

    # --------------------------------------------------------------- flipbooks

    def emit_flipbook(self, short, ref, image, animation):
        frames = max(1, image.height // image.width) if image.height > image.width else 1
        if frames < 2:
            return
        ticks = animation.get("frametime", 1)
        try:
            ticks = int(ticks)
        except (TypeError, ValueError):
            ticks = 1
        ticks = max(1, ticks)
        sheet = "textures/items/%s/%s_sheet.png" % (NAMESPACE, short)
        self.pack[sheet] = pngcodec.encode(image)
        book = self.pack_json("textures/flipbook_textures.json", [])
        book.append({
            "flipbook_texture": sheet[:-4],
            "atlas_index": 0,
            "atlas_tile_variant": 0,
            "ticks_per_frame": ticks,
            "frames": list(range(frames)),
            "blend_frames": bool(animation.get("interpolate", False)),
        })

    _json_scratch = None

    def pack_json(self, path, default):
        if self._json_scratch is None:
            self._json_scratch = {}
        return self._json_scratch.setdefault(path, default)

    # ------------------------------------------------- blocks / skulls / other

    def emit_block_skeletons(self):
        """Empty mapping skeletons for the block, skull and waypoint families.

        Geyser maps those through block-state and entity paths rather than item
        model data, so the files exist (and are validated) but stay empty until
        a server operator fills them in.
        """
        for name in ("geyser_block_mappings.json", "geyser_skull_mappings.json",
                     "geyser_waypoint_mappings.json"):
            self.skeletons = getattr(self, "skeletons", {})
            self.skeletons[name] = {"format_version": 1, "mappings": {}}

        # retextured vanilla blocks travel as Bedrock terrain textures
        blocks = {}
        for name in self.source.listdir("assets/minecraft/textures/block"):
            if not name.endswith(".png"):
                continue
            stem = posixpath.basename(name)[:-4]
            data = self.source.read(name)
            self.pack["textures/blocks/%s.png" % stem] = data
            blocks[stem] = {"textures": "textures/blocks/%s" % stem}
        self.terrain = blocks

    # ----------------------------------------------------------------- armour

    def emit_equipment(self):
        for name in self.source.names:
            if "/equipment/" not in name or not name.endswith(".json"):
                continue
            data = self.source.read_json(name)
            stem = posixpath.basename(name)[:-5]
            for layer, entries in (data.get("layers") or {}).items():
                for entry in entries:
                    ref = entry.get("texture")
                    if not ref:
                        continue
                    ns, path = packsource.PackSource.split(ref)
                    src = "assets/%s/textures/entity/equipment/%s/%s.png" % (ns, layer, path)
                    if not self.source.exists(src):
                        continue
                    dest = "textures/entity/equipment/%s/%s_%s.png" % (layer, stem, path)
                    self.pack[dest] = self.source.read(src)
                    self.armour = getattr(self, "armour", [])
                    self.armour.append((stem, layer, dest))

        for stem, layer, dest in getattr(self, "armour", []):
            if layer != "humanoid":
                continue
            for piece in ("helmet", "chestplate", "leggings", "boots"):
                short = "%s_%s" % (stem, piece)
                identifier = "%s:%s" % (NAMESPACE, short)
                if any(e["name"] == identifier for entries in self.mappings.values() for e in entries):
                    continue
                geo_id = "geometry.%s.%s" % (NAMESPACE, short)
                self.pack["models/entity/%s/%s.geo.json" % (NAMESPACE, short)] = jdump(
                    geo.humanoid_geometry(geo_id))
                self.pack["attachables/%s.json" % short] = jdump({
                    "format_version": "1.10.0",
                    "minecraft:attachable": {
                        "description": {
                            "identifier": identifier,
                            "materials": {"default": "armor", "enchanted": "armor_enchanted"},
                            "textures": {"default": dest[:-4],
                                         "enchanted": "textures/misc/enchanted_actor_glint"},
                            "geometry": {"default": geo_id},
                            "animations": {"wear": "animation.%s.wear" % NAMESPACE},
                            "scripts": {"animate": ["wear"]},
                            "render_controllers": ["controller.render.%s_armour" % NAMESPACE],
                        },
                    },
                })

    # ----------------------------------------------------------------- sounds

    def emit_sounds(self):
        definitions = {}
        for name in self.source.names:
            if not name.endswith(".ogg"):
                continue
            parts = name.split("/")
            ns = parts[1]
            stem = "/".join(parts[3:])[:-4]
            key = "%s.%s" % (ns, stem.replace("/", "."))
            dest = "sounds/%s/%s.ogg" % (ns, stem)
            self.pack[dest] = self.source.read(name)
            definitions[key] = {"category": "player", "sounds": [dest[:-4]]}
        self.sound_definitions = definitions

    # ------------------------------------------------------------------ texts

    def emit_texts(self):
        overrides = {}
        for name in self.source.names:
            if name.endswith("/lang/en_us.json"):
                try:
                    overrides.update(self.source.read_json(name))
                except ValueError:
                    pass
        self.locale_overrides = overrides
        for key, value in overrides.items():
            self.lang[key] = minimessage_to_sections(str(value))

    # ------------------------------------------------------------------- shell

    def emit_shell(self):
        item_texture = {}
        for path in self.pack:
            if path.startswith("textures/items/") and path.endswith(".png"):
                short = posixpath.basename(path)[:-4]
                item_texture[short] = {"textures": path[:-4]}
        self.item_texture = item_texture

        self.pack["textures/item_texture.json"] = jdump(
            {"resource_pack_name": "altarsmp", "texture_name": "atlas.items",
             "texture_data": {k: item_texture[k] for k in sorted(item_texture)}})
        self.pack["textures/terrain_texture.json"] = jdump(
            {"resource_pack_name": "altarsmp", "texture_name": "atlas.terrain",
             "padding": 8, "num_mip_levels": 4,
             "texture_data": {k: self.terrain[k] for k in sorted(self.terrain)}})
        flipbooks = self.pack_json("textures/flipbook_textures.json", [])
        self.pack["textures/flipbook_textures.json"] = jdump(
            sorted(flipbooks, key=lambda f: f["flipbook_texture"]))
        self.pack["sounds/sound_definitions.json"] = jdump({
            "format_version": "1.14.0",
            "sound_definitions": {k: self.sound_definitions[k] for k in sorted(self.sound_definitions)},
        })

        self.pack["animations/%s.animation.json" % NAMESPACE] = jdump({
            "format_version": "1.8.0",
            "animations": {
                "animation.%s.hold" % NAMESPACE: {
                    "loop": True,
                    "bones": {
                        "root": {
                            "rotation": ["query.is_first_person ? -6.0 : 0.0", 0.0, 0.0],
                            "position": ["query.is_first_person ? 0.0 : 0.5",
                                         "query.is_first_person ? 2.0 : 5.0",
                                         "query.is_first_person ? 0.0 : 0.0"],
                            "scale": "query.is_first_person ? 0.9 : 0.6",
                        },
                    },
                },
                "animation.%s.charge" % NAMESPACE: {
                    "loop": True,
                    "bones": {
                        "root": {
                            "rotation": [
                                "math.min(query.main_hand_item_use_duration * 6.0, 25.0) * -1.0",
                                0.0, 0.0],
                        },
                    },
                },
                "animation.%s.wear" % NAMESPACE: {"loop": True, "bones": {}},
            },
        })
        self.pack["render_controllers/%s.render_controllers.json" % NAMESPACE] = jdump({
            "format_version": "1.10.0",
            "render_controllers": {
                "controller.render.%s" % NAMESPACE: {
                    "geometry": "Geometry.default",
                    "materials": [{"*": "Material.default"}],
                    "textures": ["Texture.default"],
                },
                "controller.render.%s_armour" % NAMESPACE: {
                    "geometry": "Geometry.default",
                    "materials": [{"*": "Material.default"}],
                    "textures": ["Texture.default"],
                },
            },
        })
        self.pack["materials/%s.material" % NAMESPACE] = jdump({
            "materials": {"version": "1.0.0",
                          "altarsmp_item:entity_alphatest": {"+states": ["Blending"]}},
        })
        lines = ["## AltarSMP crossplay pack, generated - do not edit by hand"]
        for key in sorted(self.lang):
            lines.append("%s=%s" % (key, self.lang[key]))
        self.pack["texts/en_US.lang"] = ("\n".join(lines) + "\n").encode("utf-8")
        self.pack["texts/languages.json"] = jdump(["en_US"])
        self.pack["pack_icon.png"] = self.pack_icon()
        self.pack["manifest.json"] = jdump({
            "format_version": 2,
            "header": {
                "name": "AltarSMP Crossplay",
                "description": "AltarSMP 2.0.5 custom items, armour and sounds for Bedrock clients via Geyser.",
                "uuid": stable_uuid("header"),
                "version": [2, 0, 5],
                "min_engine_version": [1, 20, 0],
            },
            "modules": [{
                "type": "resources",
                "description": "AltarSMP crossplay resources",
                "uuid": stable_uuid("module", "resources"),
                "version": [2, 0, 5],
            }],
        })

    def pack_icon(self):
        for candidate in ("pack.png", "assets/altarsmp/icon.png"):
            if self.source.exists(candidate):
                image = pngcodec.decode(self.source.read(candidate))
                return pngcodec.encode(render.scale_to(image, 64))
        return pngcodec.encode(pngcodec.blank(64, 64))


# ------------------------------------------------------------------- emission

def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 3
            zf.writestr(info, entries[name])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=None)
    args = parser.parse_args()
    root = args.root or packsource.repo_root(os.path.abspath(__file__))

    build = Build(root)
    by_key, pairs, mod_pairs = build.run()

    out = build.out
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "custom_mappings"))
    os.makedirs(os.path.join(out, "validation"))
    os.makedirs(os.path.join(out, "config", "Geyser-Fabric", "locales", "overrides"))

    # the Bedrock pack must not carry Java item definitions
    assert not any(p.startswith("items/") for p in build.pack)
    write_zip(os.path.join(out, "pack.zip"), build.pack)

    mappings = {"format_version": 2,
                "items": {k: sorted(build.mappings[k], key=lambda e: e["custom_model_data"])
                          for k in sorted(build.mappings)}}
    with open(os.path.join(out, "custom_mappings", "geyser_item_mappings.json"), "wb") as fh:
        fh.write(jdump(mappings))
    for name, body in sorted(getattr(build, "skeletons", {}).items()):
        with open(os.path.join(out, "custom_mappings", name), "wb") as fh:
            fh.write(jdump(body))

    with open(os.path.join(out, "config", "Geyser-Fabric", "locales", "overrides", "en_us.json"), "wb") as fh:
        fh.write(jdump({k: build.locale_overrides[k] for k in sorted(build.locale_overrides)}))

    lines = ["# Content the crossplay package cannot ship, and why.",
             "# Generated by tools/crossplay/build_custom_content.py - do not edit by hand.", ""]
    for what, reason in sorted(set(build.missing)):
        lines.append("%s\n    %s" % (what, reason))
    lines.append("")
    lines.append("# Java model states with no Bedrock equivalent:")
    for identifier, state, note in sorted(set(build.states)):
        lines.append("%s [%s]\n    %s" % (identifier, state, note))
    lines.append("")
    lines.append("# GeyserDisplayEntity note: item displays spawned by the mod (Displays.* call")
    lines.append("# sites, tagged altarsmps2_vfx) are entity-side, not item-side. Geyser fetches")
    lines.append("# their held item through the display entity's item component, so they render")
    lines.append("# on Bedrock from the same mappings above; no extra assets are required.")
    with open(os.path.join(out, "validation", "missing-assets.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    with open(os.path.join(out, "validation", "icon-report.tsv"), "w", encoding="utf-8") as fh:
        fh.write("identifier\tbase\tcustom_model_data\ticon_source\tjava_model\n")
        for row in sorted(build.icon_rows):
            fh.write("\t".join(str(v) for v in row) + "\n")

    digest = hashlib.sha256(open(os.path.join(out, "pack.zip"), "rb").read()).hexdigest()
    size = os.path.getsize(os.path.join(out, "pack.zip"))
    print("pack.zip: %d bytes, sha256 %s" % (size, digest))
    print("mappings: %d over %d base items" %
          (sum(len(v) for v in build.mappings.values()), len(build.mappings)))
    print("icons: %d, unmappable entries: %d" % (len(build.icon_rows), len(set(build.missing))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
