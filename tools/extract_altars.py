#!/usr/bin/env python3
"""
Extracts the altar table (and the altar recipe table) from the original sources.

Each AltarSMP altar is defined by two cooperating classes:

  *AltarCommand.java   -> spawns the altar:
                          createAltar(player, "<display>", ChatColor.<C>, <cmd>,
                                      <hologram lines>, Material.<X>[, yOffset])
  *AltarInteract.java  -> handles the left click on the altar armour stand:
                          matching display name, title colour, recipe id, and the
                          item that gets handed out.

Season 2 uses a single abstract BaseAltarInteract instantiated from a/r.java with
(display name, recipe id, ChatColor, Supplier<ItemStack>).

Emits altarsmp/content/altars.json consumed by the Fabric port.
"""
from __future__ import annotations

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, os.pardir)
SRC = os.path.join(ROOT, "audit", "sources")
OUT = os.path.join(ROOT, "fabric-mod", "src", "main", "resources", "altarsmp", "content")

CHATCOLOR_MAP = {
    "GOLD": "gold", "RED": "red", "AQUA": "aqua", "GREEN": "green", "YELLOW": "yellow",
    "LIGHT_PURPLE": "light_purple", "DARK_PURPLE": "dark_purple", "DARK_RED": "dark_red",
    "BLUE": "blue", "DARK_BLUE": "dark_blue", "DARK_AQUA": "dark_aqua",
    "DARK_GREEN": "dark_green", "GRAY": "gray", "DARK_GRAY": "dark_gray",
    "WHITE": "white", "BLACK": "black",
}


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


CREATE_ALTAR = re.compile(
    r'createAltar\(\s*var\d+\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*ChatColor\.([A-Z_]+)\s*,\s*(\d+)\s*,\s*(var\d+)\s*(?:,\s*Material\.([A-Z_0-9]+))?\s*(?:,\s*([-\d.]+))?\s*\)',
    re.S)

RECIPE_LOOKUP = re.compile(r'b\.a\(\s*this\.a\s*,\s*"([a-z0-9_]+)"\s*\)')
S2_RECIPE_LOOKUP = re.compile(r'[a-z]\.a\(\s*this\.[a-z]\s*,\s*"([a-z0-9_]+)"\s*\)')


def extract_s1(d: str) -> list[dict]:
    out = []
    commands = {}
    for name in sorted(os.listdir(d)):
        if name.endswith("AltarCommand.java"):
            src = read(os.path.join(d, name))
            m = CREATE_ALTAR.search(src)
            if not m:
                continue
            recipe = RECIPE_LOOKUP.search(src)
            commands[name[:-len("AltarCommand.java")]] = {
                "display": m.group(1).replace("\\'", "'"),
                "color": CHATCOLOR_MAP.get(m.group(2), m.group(2).lower()),
                "cmd": int(m.group(3)),
                "material": m.group(5) or "NETHERITE_SWORD",
                "y_offset": float(m.group(6)) if m.group(6) else 0.0,
                "recipe": recipe.group(1) if recipe else None,
                "command_class": name[:-5],
            }
    for name in sorted(os.listdir(d)):
        if not name.endswith("AltarInteract.java"):
            continue
        stem = name[:-len("AltarInteract.java")]
        src = read(os.path.join(d, name))
        eq = re.search(r'stripColor\([^)]*\)\.equals\("((?:[^"\\]|\\.)*)"\)', src)
        display = eq.group(1).replace("\\'", "'") if eq else None
        recipe = None
        rm = re.findall(r'(?:a\.a\.[ab]|a\.[ab])\(\s*this\.a\s*,\s*var\d+\s*,\s*"([a-z0-9_]+)"\s*\)', src)
        if rm:
            recipe = rm[0]
        rm2 = re.findall(r'"([a-z0-9_]{4,})"\s*\)', src)
        colour = re.search(r'ChatColor\.([A-Z_]+)\s*,\s*"[a-z0-9_]+"', src)
        entry = commands.get(stem)
        if entry is None:
            entry = {
                "display": display,
                "color": CHATCOLOR_MAP.get(colour.group(1), "gold") if colour else "gold",
                "cmd": None,
                "material": "NETHERITE_SWORD",
                "y_offset": 0.0,
                "recipe": recipe,
                "command_class": None,
            }
        entry["interact_class"] = name[:-5]
        if display:
            entry["display"] = display
        if recipe:
            entry["recipe"] = recipe
        entry["season"] = 1
        # what the altar hands out
        gives = re.search(r'new ([A-Za-z0-9_]+Weapon)\(', src)
        entry["gives_class"] = gives.group(1) if gives else None
        out.append(entry)
    # altars that only have a command (no interact class)
    for stem, entry in commands.items():
        if not any(e.get("command_class") == entry["command_class"] for e in out):
            entry["interact_class"] = None
            entry["season"] = 1
            out.append(entry)
    return out


def extract_s2(base: str) -> list[dict]:
    out = []
    d = os.path.join(base, "com/altarsmps2/altars")
    if not os.path.isdir(d):
        return out
    r_src = read(os.path.join(base, "a/r.java")) if os.path.exists(os.path.join(base, "a/r.java")) else ""
    # new XAltarInteract(this, "Display", "recipe", ChatColor.C, Y::create)
    for m in re.finditer(
            r'new\s+([A-Za-z0-9_]+AltarInteract)\s*\(\s*this\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"([a-z0-9_]+)"\s*,\s*ChatColor\.([A-Z_]+)\s*,\s*([A-Za-z0-9_:]+)',
            r_src):
        out.append({
            "season": 2,
            "interact_class": m.group(1),
            "display": m.group(2).replace("\\'", "'"),
            "recipe": m.group(3),
            "color": CHATCOLOR_MAP.get(m.group(4), m.group(4).lower()),
            "supplier": m.group(5),
        })
    # altar spawn command in S2: a/r.java + AltarCommand.java
    ac = os.path.join(base, "com/altarsmps2/commands/AltarCommand.java")
    if os.path.exists(ac):
        src = read(ac)
        for m in re.finditer(r'"([a-z0-9_]+)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*ChatColor\.([A-Z_]+)\s*,\s*(\d+)\s*,\s*Material\.([A-Z_0-9]+)', src):
            out.append({
                "season": 2,
                "recipe": m.group(1),
                "display": m.group(2).replace("\\'", "'"),
                "color": CHATCOLOR_MAP.get(m.group(3), m.group(3).lower()),
                "cmd": int(m.group(4)),
                "material": m.group(5),
            })
    return out


# The Season 2 altar table lives in a/t.java as a static LinkedHashMap.
T_ENTRY = re.compile(
    r'a\.put\("([a-z0-9_]+)"\s*,\s*new t\.a\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*ChatColor\.([A-Z_]+)\s*,\s*(\d+)\s*,\s*"([a-z0-9_]+)"\s*,\s*(null|Material\.([A-Z_0-9]+))\s*\)\)')


def extract_s2_table(base: str) -> list[dict]:
    path = os.path.join(base, "a/t.java")
    if not os.path.exists(path):
        return []
    src = read(path)
    out = []
    for m in T_ENTRY.finditer(src):
        out.append({
            "season": 2,
            "key": m.group(1),
            "display": m.group(2).replace("\\'", "'"),
            "color": CHATCOLOR_MAP.get(m.group(3), m.group(3).lower()),
            "cmd": int(m.group(4)),
            "recipe": m.group(5),
            "material": m.group(7) or "MACE",
            "material_is_default": m.group(6) == "null",
        })
    return out


CUSTOM_ITEM_NAMES_S1 = {
    "custom_weapon_handle": "Weapon Handle",
    "custom_warden_heart": "Warden's Heart",
    "custom_hyperion_shard": "Hyperion Shard",
    "custom_nightpiercer_shard": "Nightpiercer Shard",
    "custom_illusion_core": "Illusion Core",
    "custom_vulkan_head": "Vulkan Head",
    "custom_pale_shard": "Pale Shard",
    "custom_copper_pickaxe": "Copper Pickaxe",
    "custom_copper_fragment": "Copper Fragment",
    "custom_chestplate_shard": "Chestplate Shard",
    "custom_pale_crossbow": "Pale Crossbow",
    "custom_hyperion": "Hyperion",
    "custom_nightpiercer": "Nightpiercer",
}
CUSTOM_ITEM_NAMES_S2 = {
    "custom_weapon_handle": "Weapon Handle",
    "custom_warden_heart": "Warden's Heart",
    "custom_soul_in_a_bottle": "Soul In A Bottle",
    "custom_fragment_of_the_sea": "Fragment of the Sea",
    "custom_dragon_heart": "Dragon Heart",
}
NEVER_CONSUMED = ["DRAGON_EGG"]


def extract_recipes(base: str) -> dict:
    """Parses the `recipes:` section of config.yml and s2.yml."""
    def parse(cfg_path: str) -> dict:
        text = read(cfg_path)
        lines = text.splitlines()
        out: dict[str, dict[str, int]] = {}
        in_recipes = False
        current = None
        for raw in lines:
            if re.match(r'^recipes:\s*$', raw):
                in_recipes = True
                continue
            if in_recipes:
                if re.match(r'^[A-Za-z]', raw):
                    in_recipes = False
                    continue
                m = re.match(r'^  ([a-z0-9_]+):\s*$', raw)
                if m:
                    current = m.group(1)
                    out[current] = {}
                    continue
                m = re.match(r'^    ([A-Za-z_0-9]+):\s*(\d+)\s*(?:#.*)?$', raw)
                if m and current:
                    out[current][m.group(1)] = int(m.group(2))
                    continue
                if raw.strip().startswith("#"):
                    continue
        return out

    main = parse(os.path.join(base, "config.yml"))
    s2 = parse(os.path.join(base, "s2.yml")) if os.path.exists(os.path.join(base, "s2.yml")) else {}
    return {"main": main, "s2": s2}


def main() -> int:
    if not os.path.isdir(os.path.join(SRC, "com")):
        import zipfile
        os.makedirs(SRC, exist_ok=True)
        with zipfile.ZipFile(os.path.join(ROOT, "Altar_SMPS1-2-sources-FRESH.jar")) as jar:
            jar.extractall(SRC)
    os.makedirs(OUT, exist_ok=True)
    s1 = extract_s1(os.path.join(SRC, "com/altarsmp/altars"))
    s1 = [a for a in s1 if a.get("display")]
    s2 = extract_s2_table(SRC)
    recipes = extract_recipes(SRC)
    for altar in s2:
        table = recipes["s2"].get(altar["recipe"]) or recipes["main"].get(altar["recipe"])
        if table:
            altar["ingredients"] = table

    # attach recipe ingredient tables to the altars
    for altar in s1:
        rid = altar.get("recipe")
        if rid and rid in recipes["main"]:
            altar["ingredients"] = recipes["main"][rid]
    merged = s2

    with open(os.path.join(OUT, "altars.json"), "w", encoding="utf-8") as fh:
        json.dump({"season1": s1, "season2": merged}, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "recipes.json"), "w", encoding="utf-8") as fh:
        json.dump(recipes, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "ingredients.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "custom_s1": CUSTOM_ITEM_NAMES_S1,
            "custom_s2": CUSTOM_ITEM_NAMES_S2,
            "never_consumed": NEVER_CONSUMED,
        }, fh, indent=1, ensure_ascii=False)

    print("S1 altars: %d" % len(s1))
    for a in s1:
        print("   %-28s display=%-26s recipe=%-22s cmd=%-4s mat=%-22s ing=%s" % (
            a.get("interact_class"), a.get("display"), a.get("recipe"), a.get("cmd"),
            a.get("material"), len(a.get("ingredients") or {})))
    print("S2 altars: %d" % len(merged))
    for a in merged:
        print("   %-28s display=%-26s recipe=%-22s cmd=%-4s ing=%s" % (
            a.get("interact_class") or "-", a.get("display"), a.get("recipe"), a.get("cmd"),
            len(a.get("ingredients") or {})))
    print("recipes main=%d s2=%d" % (len(recipes["main"]), len(recipes["s2"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
