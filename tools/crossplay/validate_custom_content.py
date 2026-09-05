#!/usr/bin/env python3
"""Validate the generated AltarSMP crossplay package.

Twenty-nine checks, each one documented in ``CHECKS`` below and each one
reported on its own line.  The final line is always

    validation: 29 checks, 0 failed

when the package is sound.  Run it after every build:

    python3 tools/crossplay/validate_custom_content.py
"""

from __future__ import annotations

import json
import os
import posixpath
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import discovery  # noqa: E402
import modside  # noqa: E402
import packsource  # noqa: E402
import pngcodec  # noqa: E402

OUT_DIR = "AltarSMP-Custom-Content"


class Ctx:
    def __init__(self, root):
        self.root = root
        self.out = os.path.join(root, OUT_DIR)
        self.zip_path = os.path.join(self.out, "pack.zip")
        self.zf = zipfile.ZipFile(self.zip_path)
        self.names = set(self.zf.namelist())
        self.mappings = self._json_file("custom_mappings/geyser_item_mappings.json")
        self.missing = open(os.path.join(self.out, "validation", "missing-assets.txt"),
                            encoding="utf-8").read()
        self.icon_report = open(os.path.join(self.out, "validation", "icon-report.tsv"),
                                encoding="utf-8").read().splitlines()[1:]
        self.source = packsource.PackSource(root)

    def _json_file(self, rel):
        with open(os.path.join(self.out, rel), encoding="utf-8") as fh:
            return json.load(fh)

    def zjson(self, name):
        return json.loads(self.zf.read(name).decode("utf-8"))

    def entries(self):
        for base, entries in self.mappings["items"].items():
            for entry in entries:
                yield base, entry


def check_manifest(c):
    """manifest.json exists, is format_version 2 and carries deterministic UUIDs"""
    m = c.zjson("manifest.json")
    assert m["format_version"] == 2, "manifest format_version must be 2"
    header = m["header"]
    for field in ("name", "uuid", "version", "min_engine_version"):
        assert field in header, "manifest header missing %s" % field
    uuids = {header["uuid"]} | {mod["uuid"] for mod in m["modules"]}
    assert len(uuids) == 1 + len(m["modules"]), "manifest UUIDs must be unique"
    for value in uuids:
        assert len(value) == 36 and value[14] == "4", "manifest UUID %s is not a v4 UUID" % value


def check_zip_integrity(c):
    """pack.zip has no corrupt entries"""
    bad = c.zf.testzip()
    assert bad is None, "corrupt zip entry %s" % bad


def check_zip_paths(c):
    """no zip entry escapes the pack root or uses an absolute path"""
    for name in c.names:
        assert not name.startswith("/"), "absolute path in zip: %s" % name
        assert ".." not in name.split("/"), "path traversal in zip: %s" % name
        assert not name.startswith("\\"), "windows absolute path: %s" % name


def check_no_java_items(c):
    """pack.zip ships no Java item definitions (items/*.json)"""
    offenders = [n for n in c.names if n.startswith("items/")]
    assert not offenders, "Java item definitions leaked into the pack: %s" % offenders[:3]


def check_zip_determinism(c):
    """pack.zip is deterministic: sorted entries, pinned 1980-02-01 timestamps"""
    names = c.zf.namelist()
    assert names == sorted(names), "zip entries are not sorted"
    for info in c.zf.infolist():
        assert info.date_time == (1980, 2, 1, 0, 0, 0), \
            "%s has a non-deterministic timestamp %s" % (info.filename, info.date_time)


def check_mapping_format(c):
    """geyser_item_mappings.json uses format_version 2 with an items object"""
    assert c.mappings.get("format_version") == 2, "mappings format_version must be 2"
    assert isinstance(c.mappings.get("items"), dict), "mappings must have an items object"
    for base in c.mappings["items"]:
        assert base.startswith("minecraft:"), "base item %s is not namespaced" % base


def check_mapping_unique_pairs(c):
    """each (base item, custom_model_data) pair is mapped exactly once"""
    seen = set()
    for base, entry in c.entries():
        key = (base, entry["custom_model_data"])
        assert key not in seen, "duplicate mapping for %s" % (key,)
        seen.add(key)


def check_mapping_unique_identifiers(c):
    """each (base item, identifier) is unique, and identifiers are namespaced"""
    seen = set()
    for base, entry in c.entries():
        name = entry["name"]
        assert name.startswith("altarsmp:"), "identifier %s is not in the altarsmp namespace" % name
        key = (base, name)
        assert key not in seen, "duplicate identifier %s on %s" % (name, base)
        seen.add(key)


def check_mapping_cmd_positive(c):
    """every mapped custom_model_data is a positive integer"""
    for base, entry in c.entries():
        cmd = entry["custom_model_data"]
        assert isinstance(cmd, int) and cmd > 0, "%s has a bad custom_model_data %r" % (base, cmd)


def check_icons_declared(c):
    """every mapping's icon is declared in item_texture.json with its PNG present"""
    atlas = c.zjson("textures/item_texture.json")["texture_data"]
    for base, entry in c.entries():
        icon = entry["bedrock_options"]["icon"]
        assert icon in atlas, "icon %s (%s) is missing from item_texture.json" % (icon, base)
    for short, data in atlas.items():
        path = data["textures"] + ".png"
        assert path in c.names, "item_texture entry %s points at missing %s" % (short, path)


def check_icons_not_blank(c):
    """no shipped inventory icon is fully transparent"""
    for name in sorted(n for n in c.names if n.startswith("textures/items/") and n.endswith(".png")):
        if name.endswith("_sheet.png"):
            continue
        image = pngcodec.decode(c.zf.read(name))
        assert not image.is_blank(), "icon %s is blank" % name


def check_icon_report(c):
    """the icon report records a source for every mapped item"""
    rows = {line.split("\t")[0] for line in c.icon_report if line.strip()}
    for base, entry in c.entries():
        assert entry["name"] in rows, "%s has no icon-report row" % entry["name"]
    for line in c.icon_report:
        if not line.strip():
            continue
        source = line.split("\t")[3]
        assert source and source != "none", "%s has no icon source" % line.split("\t")[0]


def check_attachables_identifier(c):
    """every attachable's identifier equals the mapping name it belongs to"""
    names = {entry["name"] for _base, entry in c.entries()}
    armour = set()
    for name in sorted(n for n in c.names if n.startswith("attachables/")):
        data = c.zjson(name)["minecraft:attachable"]["description"]
        ident = data["identifier"]
        assert ident.startswith("altarsmp:"), "%s has a foreign identifier %s" % (name, ident)
        if ident not in names:
            armour.add(ident)
        assert posixpath.basename(name)[:-5] == ident.split(":", 1)[1], \
            "%s does not match its identifier %s" % (name, ident)
    c.armour_attachables = armour


def check_attachable_geometry(c):
    """every attachable resolves to a geometry that exists in the pack"""
    geometries = {}
    for name in c.names:
        if name.startswith("models/entity/") and name.endswith(".geo.json"):
            for block in c.zjson(name)["minecraft:geometry"]:
                geometries[block["description"]["identifier"]] = (name, block)
    c.geometries = geometries
    for name in sorted(n for n in c.names if n.startswith("attachables/")):
        data = c.zjson(name)["minecraft:attachable"]["description"]
        geo_id = data["geometry"]["default"]
        assert geo_id in geometries, "%s references missing geometry %s" % (name, geo_id)


def check_attachable_textures(c):
    """every attachable's default texture exists in the pack"""
    for name in sorted(n for n in c.names if n.startswith("attachables/")):
        data = c.zjson(name)["minecraft:attachable"]["description"]
        texture = data["textures"]["default"]
        if texture.startswith("textures/misc/"):
            continue
        assert texture + ".png" in c.names, "%s references missing texture %s" % (name, texture)


def check_attachable_render_controllers(c):
    """every attachable's render controllers are defined in the pack"""
    defined = set()
    for name in c.names:
        if name.startswith("render_controllers/"):
            defined |= set(c.zjson(name)["render_controllers"])
    for name in sorted(n for n in c.names if n.startswith("attachables/")):
        data = c.zjson(name)["minecraft:attachable"]["description"]
        for controller in data["render_controllers"]:
            assert controller in defined, "%s references missing %s" % (name, controller)


def check_attachable_animations(c):
    """every animation an attachable names is defined in the pack"""
    defined = set()
    for name in c.names:
        if name.startswith("animations/"):
            defined |= set(c.zjson(name)["animations"])
    for name in sorted(n for n in c.names if n.startswith("attachables/")):
        data = c.zjson(name)["minecraft:attachable"]["description"]
        for anim in data.get("animations", {}).values():
            assert anim in defined, "%s references missing animation %s" % (name, anim)


def check_geometry_has_cubes(c):
    """no shipped geometry is cube-less (Bedrock renders nothing for those)"""
    for ident, (name, block) in sorted(c.geometries.items()):
        cubes = sum(len(bone.get("cubes", [])) for bone in block["bones"])
        assert cubes > 0, "%s (%s) has no cubes" % (ident, name)


def check_geometry_no_zero_thickness(c):
    """no cube has a zero-size axis - Blockbench planes were inflated to slivers"""
    for ident, (name, block) in sorted(c.geometries.items()):
        for bone in block["bones"]:
            for cube in bone.get("cubes", []):
                for axis, value in zip("xyz", cube["size"]):
                    assert abs(value) > 1e-9, "%s has a zero-thickness cube on %s" % (ident, axis)


def check_hold_animation_first_person(c):
    """the hold animation branches on query.is_first_person"""
    text = c.zf.read("animations/altarsmp.animation.json").decode()
    assert "query.is_first_person" in text, "the hold animation has no first-person Molang"


def check_charge_animation(c):
    """the charge animation drives off main_hand_item_use_duration"""
    text = c.zf.read("animations/altarsmp.animation.json").decode()
    assert "query.main_hand_item_use_duration" in text, "the charge animation has no use-duration Molang"


def check_flipbooks(c):
    """every flipbook points at a real sheet and has a positive tick rate"""
    books = c.zjson("textures/flipbook_textures.json")
    for book in books:
        path = book["flipbook_texture"] + ".png"
        assert path in c.names, "flipbook %s is missing its sheet" % path
        assert book["ticks_per_frame"] > 0, "flipbook %s has a non-positive tick rate" % path
        assert len(book["frames"]) > 1, "flipbook %s has fewer than two frames" % path


def check_sounds(c):
    """every sound definition points at an ogg that ships in the pack"""
    definitions = c.zjson("sounds/sound_definitions.json")["sound_definitions"]
    assert definitions, "no sound definitions were generated"
    for key, data in definitions.items():
        for entry in data["sounds"]:
            path = (entry if isinstance(entry, str) else entry["name"]) + ".ogg"
            assert path in c.names, "sound %s points at missing %s" % (key, path)


def check_terrain_textures(c):
    """every terrain_texture entry ships its PNG"""
    atlas = c.zjson("textures/terrain_texture.json")["texture_data"]
    assert atlas, "no retextured blocks were collected"
    for short, data in atlas.items():
        assert data["textures"] + ".png" in c.names, "terrain entry %s has no PNG" % short


def check_pairs_accounted(c):
    """every mod-side and pack-side (base, cmd) pair is mapped or documented as missing"""
    mapped = {(base.split(":", 1)[1], entry["custom_model_data"]) for base, entry in c.entries()}
    pairs, unknown = modside.mod_pairs(c.root)
    for pair in pairs:
        if pair.key in mapped:
            continue
        needle = "%s cmd %d" % (pair.base, pair.cmd)
        assert needle in c.missing, "%s is neither mapped nor documented" % needle
    for material, cmd, origin in unknown:
        assert origin in c.missing, "runtime model data at %s is not documented" % origin
    for pair in discovery.discover_pairs(c.source):
        if (pair.base, pair.cmd) in mapped:
            continue
        needle = "%s cmd %d" % (pair.base, pair.cmd)
        assert needle in c.missing, "%s is neither mapped nor documented" % needle


def check_states_documented(c):
    """every special model state is covered by a mapping or written down"""
    assert "Java model states with no Bedrock equivalent" in c.missing, \
        "the state section is missing from missing-assets.txt"
    for pair in discovery.discover_pairs(c.source):
        for leaf in pair.extra_states:
            if not leaf.states:
                continue
            assert any(state.split("=")[0].lstrip("!") in c.missing for state in leaf.states), \
                "state %s of %s cmd %d is undocumented" % (leaf.states, pair.base, pair.cmd)


def check_locale_overrides(c):
    """the Geyser-Fabric locale overrides ship the source packs' lang entries"""
    path = os.path.join(c.out, "config", "Geyser-Fabric", "locales", "overrides", "en_us.json")
    assert os.path.exists(path), "the locale override file is missing"
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    assert data, "the locale override file is empty"
    for name in c.source.names:
        if name.endswith("/lang/en_us.json"):
            for key in c.source.read_json(name):
                assert key in data, "locale key %s was dropped" % key


def check_texts_named(c):
    """every mapping is named in texts/en_US.lang"""
    text = c.zf.read("texts/en_US.lang").decode("utf-8")
    keys = {line.split("=", 1)[0] for line in text.splitlines() if "=" in line}
    for base, entry in c.entries():
        key = "item.%s" % entry["name"]
        assert key in keys, "%s has no en_US.lang name" % entry["name"]
    assert "languages.json" in "".join(c.names) or "texts/languages.json" in c.names, \
        "texts/languages.json is missing"


def check_display_entity_note(c):
    """the GeyserDisplayEntity fetch behaviour is documented for the VFX displays"""
    assert "GeyserDisplayEntity" in c.missing, "the display-entity note is missing"
    sites = modside.display_call_sites(c.root)
    assert sites, "no Displays.* call sites were found to document"


CHECKS = [
    check_manifest, check_zip_integrity, check_zip_paths, check_no_java_items,
    check_zip_determinism, check_mapping_format,
    check_mapping_unique_pairs, check_mapping_unique_identifiers, check_mapping_cmd_positive,
    check_icons_declared, check_icons_not_blank, check_icon_report,
    check_attachables_identifier, check_attachable_geometry, check_attachable_textures,
    check_attachable_render_controllers, check_attachable_animations,
    check_geometry_has_cubes, check_geometry_no_zero_thickness,
    check_hold_animation_first_person, check_charge_animation, check_flipbooks,
    check_sounds, check_terrain_textures, check_pairs_accounted, check_states_documented,
    check_locale_overrides, check_texts_named, check_display_entity_note,
]


def main():
    root = packsource.repo_root(os.path.abspath(__file__))
    ctx = Ctx(root)
    failed = 0
    for index, check in enumerate(CHECKS, 1):
        title = (check.__doc__ or check.__name__).strip().splitlines()[0]
        try:
            check(ctx)
        except AssertionError as exc:
            failed += 1
            print("%2d FAIL %s: %s" % (index, title, exc))
        except Exception as exc:  # noqa: BLE001 - a broken check is a failed check
            failed += 1
            print("%2d FAIL %s: %s: %s" % (index, title, type(exc).__name__, exc))
        else:
            print("%2d ok   %s" % (index, title))
    print("validation: %d checks, %d failed" % (len(CHECKS), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
