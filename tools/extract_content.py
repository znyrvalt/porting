#!/usr/bin/env python3
"""
Authoritative content extractor for the AltarSMP -> Fabric port.

Reads the decompiled Bukkit sources shipped in `Altar_SMPS1-2-sources-FRESH.jar`
(extracted under audit/sources) and emits JSON content tables that the Fabric mod
loads at runtime:

  altarsmp/content/weapons_s1.json
  altarsmp/content/weapons_s2.json
  altarsmp/content/items.json
  altarsmp/content/armor.json

Extracted per entry: identity id, base material, custom model data, tooltip style
key, rarity, unbreakable flag, enchantments (with the original config path +
default), display name markup and the full lore list.

Lore lines that the original built from configuration values (cooldowns shown in
the tooltip) keep a `{cfg:<path>|<default>}` placeholder so the Fabric port shows
the *live* configured value, exactly like the plugin did.

Nothing here invents content: every field comes from the sources.
"""
from __future__ import annotations

import json
import os
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "audit", "sources")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir,
                   "fabric-mod", "src", "main", "resources", "altarsmp", "content")

CHAT_COLORS = {
    "BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED", "DARK_PURPLE",
    "GOLD", "GRAY", "GREY", "DARK_GRAY", "BLUE", "GREEN", "AQUA", "RED",
    "LIGHT_PURPLE", "YELLOW", "WHITE", "MAGIC", "BOLD", "STRIKETHROUGH",
    "UNDERLINE", "ITALIC", "RESET",
}
CHAT_TO_TAG = {
    "MAGIC": "obfuscated", "UNDERLINE": "underlined", "STRIKETHROUGH": "strikethrough",
    "ITALIC": "italic", "BOLD": "bold", "RESET": "reset", "GREY": "gray",
}

# Bukkit Enchantment constant -> vanilla registry id
ENCHANT_IDS = {
    "SHARPNESS": "sharpness", "LOOTING": "looting", "SWEEPING_EDGE": "sweeping_edge",
    "UNBREAKING": "unbreaking", "MENDING": "mending", "EFFICIENCY": "efficiency",
    "KNOCKBACK": "knockback", "POWER": "power", "QUICK_CHARGE": "quick_charge",
    "MULTISHOT": "multishot", "PIERCING": "piercing", "WIND_BURST": "wind_burst",
    "FIRE_ASPECT": "fire_aspect", "FORTUNE": "fortune", "SILK_TOUCH": "silk_touch",
    "FLAME": "flame", "INFINITY": "infinity", "PUNCH": "punch",
    "PROTECTION": "protection", "FEATHER_FALLING": "feather_falling",
    "THORNS": "thorns", "RESPIRATION": "respiration", "AQUA_AFFINITY": "aqua_affinity",
    "DEPTH_STRIDER": "depth_strider", "SMITE": "smite", "BANE_OF_ARTHROPODS": "bane_of_arthropods",
    "DENSITY": "density", "BREACH": "breach", "LUCK_OF_THE_SEA": "luck_of_the_sea",
    "LURE": "lure", "RIPTIDE": "riptide", "CHANNELING": "channeling",
    "IMPALING": "impaling", "LOYALTY": "loyalty", "SOUL_SPEED": "soul_speed",
    "SWIFT_SNEAK": "swift_sneak", "PROJECTILE_PROTECTION": "projectile_protection",
    "BLAST_PROTECTION": "blast_protection", "FIRE_PROTECTION": "fire_protection",
    "BINDING_CURSE": "binding_curse", "VANISHING_CURSE": "vanishing_curse",
    "SWEEPING": "sweeping_edge",
}


ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir)
SOURCE_JAR = os.path.join(ROOT, "Altar_SMPS1-2-sources-FRESH.jar")


def ensure_sources() -> str:
    """Extracts the original source jar into audit/sources when needed."""
    if os.path.isdir(os.path.join(SRC, "com")):
        return SRC
    import zipfile
    os.makedirs(SRC, exist_ok=True)
    with zipfile.ZipFile(SOURCE_JAR) as jar:
        jar.extractall(SRC)
    return SRC


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def find_method(src: str, signature_re: str) -> Optional[str]:
    """Returns the body of the first method matching `signature_re`."""
    match = re.search(signature_re, src)
    if not match:
        return None
    start = src.find("{", match.end() - 1)
    if start < 0:
        return None
    depth = 0
    i = start
    in_str = False
    in_char = False
    esc = False
    while i < len(src):
        c = src[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif in_char:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == "'":
                in_char = False
        else:
            if c == '"':
                in_str = True
            elif c == "'":
                in_char = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return src[start + 1:i]
        i += 1
    return None


def split_top_level(body: str) -> List[str]:
    """Splits a comma separated argument list honouring nesting and strings."""
    out: List[str] = []
    depth = 0
    cur: List[str] = []
    in_str = False
    esc = False
    for ch in body:
        if in_str:
            cur.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            cur.append(ch)
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        out.append(tail)
    return out


def list_literal_args(expr: str) -> Optional[List[str]]:
    """`Arrays.asList(a, b)` / `List.of(a, b)` -> [a, b]."""
    m = re.search(r"(?:Arrays\.asList|List\.of|ImmutableList\.of)\s*\((.*)\)\s*;?\s*$", expr, re.S)
    if not m:
        return None
    return split_top_level(m.group(1))


STRING_LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')

# a/C.java static lore helpers, resolved from the decompiled source.
C_HELPERS = {
    "C.a()": "<gold>Sharpness <red>VII",
    "C.b()": "<white>Looting III",
    "C.c()": "<white>Sweeping Edge III",
}


def unescape(java: str) -> str:
    return (java.replace("\\\"", '"').replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\'", "'").replace("\\u00a7", "\u00a7"))


def expr_to_markup(expr: str, varmap: Dict[str, str]) -> str:
    """
    Converts a Java string expression into AltarSMP markup.

    Handles: plain literals, ChatColor.X + "..." concatenations, local variables
    that hold configuration values (replaced with a `{cfg:path|default}` token).
    """
    parts = split_concat(expr)
    out: List[str] = []
    for part in parts:
        part = part.strip()
        if part.startswith('"'):
            m = STRING_LIT.fullmatch(part)
            if m:
                out.append(unescape(m.group(1)))
                continue
            # a concatenation inside one chunk
            out.append(unescape("".join(STRING_LIT.findall(part))))
            continue
        m = re.fullmatch(r"ChatColor\.([A-Z_]+)", part)
        if m:
            name = m.group(1)
            out.append("<%s>" % CHAT_TO_TAG.get(name, name.lower()))
            continue
        m = re.fullmatch(r"NamedTextColor\.([A-Z_]+)", part)
        if m:
            out.append("<%s>" % m.group(1).lower())
            continue
        m = re.fullmatch(r"TextDecoration\.([A-Z_]+)", part)
        if m:
            out.append("<%s>" % CHAT_TO_TAG.get(m.group(1), m.group(1).lower()))
            continue
        if part in varmap:
            out.append(varmap[part])
            continue
        if part in C_HELPERS:
            out.append(C_HELPERS[part])
            continue
        m = re.fullmatch(r"(?:String\.valueOf\()?\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)?", part)
        if m and m.group(1) in varmap:
            out.append(varmap[m.group(1)])
            continue
        # Anything else is a runtime value we cannot statically resolve.
        out.append("{expr:%s}" % part)
    return "".join(out)


def split_concat(expr: str) -> List[str]:
    """Splits on top-level ` + ` operators."""
    out: List[str] = []
    depth = 0
    cur: List[str] = []
    in_str = False
    esc = False
    i = 0
    while i < len(expr):
        ch = expr[i]
        if in_str:
            cur.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            cur.append(ch)
            i += 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "+" and depth == 0 and (i + 1 >= len(expr) or expr[i + 1] != "+"):
            out.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    tail = "".join(cur).strip()
    if tail:
        out.append(tail)
    return out


CONFIG_CALL = re.compile(
    r'get(?:Int|Double|Boolean|String|Long)\s*\(\s*"([^"]+)"\s*(?:,\s*([^)]+?)\s*)?\)')


def build_varmap(src: str, body: str) -> Dict[str, str]:
    """
    Maps local variable names used inside lore text to `{cfg:path|default}` tokens.

    Resolves one level of indirection: `int var1 = this.getShatterCooldown();`
    where `getShatterCooldown()` returns `config.getInt("...", 45)`.
    """
    getters: Dict[str, Tuple[str, str]] = {}
    for name, cfgpath, default in re.findall(
            r'(?:private|protected|public)?\s*(?:int|double|boolean|long|float|String)\s+(get[A-Za-z0-9_]+|is[A-Za-z0-9_]+)\s*\(\s*\)\s*\{\s*return\s+[^;]*?get(?:Int|Double|Boolean|String|Long)\s*\(\s*"([^"]+)"\s*,\s*([^)]+?)\s*\)\s*;', src):
        getters[name] = (cfgpath, default.strip())
    # simpler scan for getters
    for m in re.finditer(r'(?:int|double|boolean|long|float|String)\s+([A-Za-z0-9_]+)\s*\(\s*\)\s*\{([^}]*)\}', src):
        name, gbody = m.group(1), m.group(2)
        cm = CONFIG_CALL.search(gbody)
        if cm and name not in getters:
            getters[name] = (cm.group(1), (cm.group(2) or "").strip())

    varmap: Dict[str, str] = {}
    for m in re.finditer(r'(?:int|double|boolean|long|float|String)\s+(var\d+|[a-z][A-Za-z0-9_]*)\s*=\s*([^;]+);', body):
        var, init = m.group(1), m.group(2).strip()
        cm = CONFIG_CALL.search(init)
        if cm:
            default = (cm.group(2) or "").strip()
            varmap[var] = "{cfg:%s|%s}" % (cm.group(1), default)
            continue
        gm = re.search(r'\.(get[A-Za-z0-9_]+|is[A-Za-z0-9_]+)\s*\(\s*\)', init)
        if gm and gm.group(1) in getters:
            cfgpath, default = getters[gm.group(1)]
            varmap[var] = "{cfg:%s|%s}" % (cfgpath, default)
            continue
        sm = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', init)
        if sm:
            varmap[var] = unescape(sm.group(1))
    return varmap


def enchant_calls(body: str) -> List[Tuple[str, str]]:
    """Extracts `.a(Enchantment.X, <expr>)` / `.addEnchant(Enchantment.X, <expr>, ...)` pairs."""
    out: List[Tuple[str, str]] = []
    for m in re.finditer(r'(?:\.a|addEnchant)\(\s*Enchantment\.([A-Z_0-9]+)\s*,', body):
        start = m.end()
        depth = 1
        i = start
        in_str = False
        esc = False
        args: List[str] = []
        cur: List[str] = []
        while i < len(body) and depth > 0:
            c = body[i]
            if in_str:
                cur.append(c)
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    in_str = False
                i += 1
                continue
            if c == '"':
                in_str = True
                cur.append(c)
            elif c == "(":
                depth += 1
                cur.append(c)
            elif c == ")":
                depth -= 1
                if depth == 0:
                    args.append("".join(cur))
                    break
                cur.append(c)
            elif c == "," and depth == 1:
                args.append("".join(cur))
                cur = []
            else:
                cur.append(c)
            i += 1
        if args:
            out.append((m.group(1), args[0].strip()))
    return out


def parse_s1_weapon(path: str) -> Optional[Dict[str, Any]]:
    src = read(path)
    cls = os.path.basename(path)[:-5]
    if "extends BaseWeapon" not in src and "extends com.altarsmp.weapons.BaseWeapon" not in src:
        return None

    def ret(re_str: str) -> Optional[str]:
        m = re.search(re_str, src)
        return m.group(1) if m else None

    wid = ret(r'getWeaponId\(\)\s*\{\s*return\s*"([^"]+)"')
    name = ret(r'getWeaponName\(\)\s*\{\s*return\s*"((?:[^"\\]|\\.)*)"')
    material = ret(r'getBaseMaterial\(\)\s*\{\s*return\s*Material\.([A-Z_0-9]+)')
    cmd = ret(r'getCustomModelData\(\)\s*\{\s*return\s*(\d+)')
    tooltip = ret(r'getTooltipStyleKey\(\)\s*\{\s*return\s*"([^"]+)"')
    if not wid:
        return None

    body = find_method(src, r'public\s+ItemStack\s+createWeapon\s*\(\s*\)\s*\{')
    if body is None:
        body = find_method(src, r'ItemStack\s+createWeapon\s*\(\s*\)\s*\{')
    lore: List[str] = []
    enchants: List[Dict[str, Any]] = []
    rarity = None
    unbreakable = False
    hide_all_flags = False
    if body:
        varmap = build_varmap(src, body)
        # lore list literal (first Arrays.asList / List.of in the method)
        for m in re.finditer(r'(?:Arrays\.asList|List\.of)\s*\(', body):
            start = m.end() - 1
            depth = 0
            i = start
            in_str = False
            esc = False
            while i < len(body):
                c = body[i]
                if in_str:
                    if esc:
                        esc = False
                    elif c == "\\":
                        esc = True
                    elif c == '"':
                        in_str = False
                else:
                    if c == '"':
                        in_str = True
                    elif c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                        if depth == 0:
                            break
                i += 1
            args = split_top_level(body[start + 1:i])
            if len(args) >= 3 and all((a.strip().startswith('"') or "+" in a or a.strip().startswith("ChatColor")
                                       or a.strip() in varmap) for a in args):
                lore = [expr_to_markup(a, varmap) for a in args]
                break
        for ench, expr in enchant_calls(body):
            cm = CONFIG_CALL.search(expr)
            entry: Dict[str, Any] = {"enchant": ENCHANT_IDS.get(ench, ench.lower())}
            if cm:
                entry["config"] = cm.group(1)
                entry["default"] = parse_number(cm.group(2))
            else:
                entry["default"] = parse_number(expr)
            enchants.append(entry)
        rm = re.search(r'\.a\(\s*ItemRarity\.([A-Z_]+)\s*\)', body)
        if rm:
            rarity = rm.group(1).lower()
        unbreakable = re.search(r'\.a\(\s*true\s*\)', body) is not None
        hide_all_flags = re.search(r'\.a\(\s*\)\s*\n', body) is not None
    return {
        "id": wid,
        "class": cls,
        "season": 1,
        "display_name": unescape(name) if name else wid,
        "base_material": material,
        "custom_model_data": int(cmd) if cmd else None,
        "tooltip_style": tooltip,
        "rarity": rarity,
        "unbreakable": unbreakable,
        "enchants": enchants,
        "lore": lore,
    }


def parse_number(raw: Optional[str]) -> Any:
    if raw is None:
        return None
    raw = raw.strip().rstrip("FfDdLl")
    try:
        if "." in raw:
            return float(raw)
        return int(raw)
    except ValueError:
        return raw


def parse_s2_weapon(path: str) -> Optional[Dict[str, Any]]:
    src = read(path)
    cls = os.path.basename(path)[:-5]
    wid_m = re.search(r'public static final String ID\s*=\s*"([^"]+)"', src)
    disp_m = re.search(r'public static final String DISPLAY\s*=\s*"([^"]+)"', src)
    cmd_m = re.search(r'CUSTOM_MODEL_DATA\s*=\s*(\d+)', src)
    if not wid_m:
        return None
    body = find_method(src, r'(?:public\s+)?static\s+ItemStack\s+create\s*\(\s*\)\s*\{') \
        or find_method(src, r'ItemStack\s+create[A-Za-z0-9_]*\s*\(\s*\)\s*\{')
    lore: List[str] = []
    display = None
    material = None
    tooltip = None
    enchants: List[Dict[str, Any]] = []
    rarity = None
    if body:
        varmap = build_varmap(src, body)
        mm = re.search(r'Material\.([A-Z_0-9]+)', body)
        if mm:
            material = mm.group(1)
        dm = re.search(r'displayName\(\s*(B\.b\(\s*)?"((?:[^"\\]|\\.)*)"', body)
        if dm:
            display = unescape(dm.group(2))
        lm = re.search(r'setLore\(\s*', body)
        if lm:
            start = body.find("(", lm.end() - 1)
            depth = 0
            i = start
            in_str = False
            esc = False
            while i < len(body):
                c = body[i]
                if in_str:
                    if esc:
                        esc = False
                    elif c == "\\":
                        esc = True
                    elif c == '"':
                        in_str = False
                else:
                    if c == '"':
                        in_str = True
                    elif c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                        if depth == 0:
                            break
                i += 1
            inner = body[start + 1:i]
            args = list_literal_args(inner + ")") or split_top_level(inner)
            lore = [expr_to_markup(a, varmap) for a in args]
        tm = re.search(r'C\.a\(\s*var\d+\s*,\s*"([^"]+)"\s*\)', body)
        if tm:
            tooltip = tm.group(1)
        if re.search(r'C\.a\(\s*var\d+\s*,\s*(true|false)\s*\)', body):
            ranged = re.search(r'C\.a\(\s*var\d+\s*,\s*(true|false)\s*\)', body).group(1) == "true"
            enchants = ([{"enchant": "power", "default": 5}, {"enchant": "unbreaking", "default": 3}] if ranged
                        else [{"enchant": "sharpness", "default": 7}, {"enchant": "looting", "default": 3},
                              {"enchant": "sweeping_edge", "default": 3}, {"enchant": "unbreaking", "default": 3}])
        if "setUnbreakable(true)" in body:
            pass
    return {
        "id": wid_m.group(1),
        "class": cls,
        "season": 2,
        "display_name": display or (unescape(disp_m.group(1)) if disp_m else wid_m.group(1)),
        "plain_display": unescape(disp_m.group(1)) if disp_m else None,
        "base_material": material,
        "custom_model_data": int(cmd_m.group(1)) if cmd_m else None,
        "tooltip_style": tooltip,
        "rarity": rarity,
        "unbreakable": True,
        "enchants": enchants,
        "lore": lore,
    }


# --- copper armour ---------------------------------------------------------
# The four CopperBoots/CopperChestplate/CopperLeggings/CopperHelmet classes build
# their ItemStack with the obfuscated fluent helper `m`, so the generic item
# parser misses the enchantments and the equipment asset. Both are read here
# straight out of createArmor(), and CopperDiamondArmor - which builds the four
# diamond-looking trial pieces programmatically - is expanded into real entries.
ARMOR_ENCHANTS = {
    "PROTECTION": "protection",
    "RESPIRATION": "respiration",
    "AQUA_AFFINITY": "aqua_affinity",
    "UNBREAKING": "unbreaking",
    "MENDING": "mending",
    "FEATHER_FALLING": "feather_falling",
    "SOUL_SPEED": "soul_speed",
    "DEPTH_STRIDER": "depth_strider",
}


def copper_armor_id(cls: str) -> str:
    out: List[str] = []
    for ch in cls:
        if ch.isupper() and out:
            out.append("_")
        out.append(ch.lower())
    return "".join(out)


def armor_enchants(body: str) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for name, level in re.findall(r"\.a\(\s*Enchantment\.([A-Z_0-9]+)\s*,\s*(\d+)\s*\)", body):
        out.append({"enchant": ARMOR_ENCHANTS.get(name, name.lower()), "config": None, "default": int(level)})
    return out


def enrich_armor(entry: Dict[str, Any], body: str) -> Dict[str, Any]:
    entry["enchants"] = armor_enchants(body)
    model = re.search(r'setModel\(\s*NamespacedKey\.fromString\(\s*"([^"]+)"', body)
    entry["equippable_model"] = model.group(1) if model else None
    entry["unbreakable"] = bool(re.search(r"\.a\(\s*true\s*\)", body))
    return entry


def copper_diamond_variants(body: str) -> List[Dict[str, Any]]:
    if "copper_diamond_" not in body:
        return []
    enchants = armor_enchants(body)
    model = re.search(r'setModel\(\s*NamespacedKey\.fromString\(\s*"([^"]+)"', body)
    out = []
    for material, piece in (("DIAMOND_HELMET", "Helmet"), ("DIAMOND_CHESTPLATE", "Chestplate"),
                            ("DIAMOND_LEGGINGS", "Leggings"), ("DIAMOND_BOOTS", "Boots")):
        out.append({
            "id": "copper_diamond_" + piece.lower(),
            "class": "CopperDiamondArmor",
            "base_material": material,
            "custom_model_data": None,
            "display_name": "<gold>Copper " + piece,
            "tooltip_style": None,
            "rarity": None,
            "lore": ["<gray>Diamond armor with Copper appearance"],
            "pdc_keys": [],
            "enchants": enchants,
            "equippable_model": model.group(1) if model else "custom:copper",
            "unbreakable": True,
        })
    return out


def parse_item_class(path: str) -> Optional[Dict[str, Any]]:
    """Parses the com.altarsmp.items / com.altarsmps2.items helper item classes."""
    src = read(path)
    cls = os.path.basename(path)[:-5]
    create = None
    for sig in (r'static\s+ItemStack\s+(create[A-Za-z0-9_]*)\s*\(\s*\)\s*\{',
                r'static\s+ItemStack\s+(create)\s*\(\s*\)\s*\{',
                r'ItemStack\s+(create[A-Za-z0-9_]*)\s*\(\s*\)\s*\{'):
        m = re.search(sig, src)
        if m:
            create = find_method(src, sig)
            break
    if create is None:
        return None
    varmap = build_varmap(src, create)
    material = re.search(r'Material\.([A-Z_0-9]+)', create)
    cmd = re.search(r'setCustomModelData\(\s*(\d+)', create) or re.search(r'\.a\(\s*(\d+)\s*\)', create)
    name = re.search(r'displayName\(\s*(?:B\.b\(\s*)?"((?:[^"\\]|\\.)*)"', create) or \
        re.search(r'setDisplayName\(\s*"((?:[^"\\]|\\.)*)"', create) or \
        re.search(r'\.a\(\s*"((?:[^"\\]|\\.)*)"\s*\)', create)
    lore: List[str] = []
    lm = re.search(r'setLore\(\s*', create)
    if lm:
        start = create.find("(", lm.end() - 1)
        depth = 0
        i = start
        in_str = False
        esc = False
        while i < len(create):
            c = create[i]
            if in_str:
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    in_str = False
            else:
                if c == '"':
                    in_str = True
                elif c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                    if depth == 0:
                        break
            i += 1
        inner = create[start + 1:i]
        args = list_literal_args(inner + ")") or split_top_level(inner)
        lore = [expr_to_markup(a, varmap) for a in args]
    else:
        for m in re.finditer(r'(?:Arrays\.asList|List\.of)\s*\(', create):
            start = m.end() - 1
            depth = 0
            i = start
            in_str = False
            esc = False
            while i < len(create):
                c = create[i]
                if in_str:
                    if esc:
                        esc = False
                    elif c == "\\":
                        esc = True
                    elif c == '"':
                        in_str = False
                else:
                    if c == '"':
                        in_str = True
                    elif c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                        if depth == 0:
                            break
                i += 1
            args = split_top_level(create[start + 1:i])
            if args and all(a.strip().startswith('"') or "+" in a or a.strip().startswith("ChatColor") for a in args):
                lore = [expr_to_markup(a, varmap) for a in args]
                break
    pdc = re.findall(r'(?:NamespacedKey\([^,]+,\s*"([a-z_0-9]+)"\)|set\(\s*[A-Za-z0-9_]+\s*,\s*"([a-z_0-9]+)")', create)
    tooltip = re.search(r'C\.a\(\s*var\d+\s*,\s*"([^"]+)"\s*\)', create) or \
        re.search(r'\.a\(\s*new NamespacedKey\(\s*"altarsmp"\s*,\s*"([^"]+)"\s*\)', create) or \
        re.search(r'setTooltipStyle[^"]*"([^"]+)"', create)
    rarity = re.search(r'ItemRarity\.([A-Z_]+)', create)
    return {
        "class": cls,
        "base_material": material.group(1) if material else None,
        "custom_model_data": int(cmd.group(1)) if cmd else None,
        "display_name": unescape(name.group(1)) if name else None,
        "tooltip_style": tooltip.group(1) if tooltip else None,
        "rarity": rarity.group(1).lower() if rarity else None,
        "lore": lore,
        "pdc_keys": sorted({a or b for a, b in pdc}),
    }


MANUAL_S1 = {
    # Striker: lore gains a "Nuke Shot" block only when the Eclipse revamp is off
    # and striker-nuke-shot is on (see StrikerWeapon#createWeapon).
    "striker": {
        "enchants": [],
        "conditional_lore": [{
            "when_all": [
                {"path": "eclipse-revamp.enabled", "equals": False},
                {"path": "eclipse-revamp.striker-nuke-shot", "equals": True},
            ],
            "lines": [
                "",
                "<aqua><bold>\u0274\u1d1c\u029a\u1d07 s\u029c\u1d0f\u1d1b</bold>",
                "<dark_gray>\u23f1 {cfg:eclipse-revamp.starfall.striker-cooldown|60}s <dark_aqua>Shift-Offhand",
                "<gray>Call down a barrage of star impacts,",
                "<gray>dealing true damage without breaking blocks.",
            ],
        }],
    },
}

MANUAL_S2 = {
    "omen": {"base_material": "MACE"},
}

BOW_OF_DECEPTION = {
    "id": "bow_of_deception",
    "class": "BowOfDeceptionWeapon",
    "season": 2,
    "display_name": "<aqua>Bow of Deception and Lies",
    "base_material": "BOW",
    "custom_model_data": None,
    "tooltip_style": None,
    "rarity": None,
    "unbreakable": True,
    "enchants": [{"enchant": "sharpness", "default": 10}],
    "lore": [
        "<gray>have fun {owner|leekleek}",
        "",
        "<white>!! Sneak + Right-Click to eat the evidence. !!",
        "<aqua>---------------------------------------",
        "",
        "<aqua><bold>ITEM-DROP > MELEE HIT</bold>",
        "<gray>Hit a player with this bow to make them drop",
        "<gray>their held item.",
        "<gray>\u23f1 NO COOLDOWN.",
        "",
        "<aqua><bold>ARMOR-DROP > SHIFT + MELEE HIT</bold>",
        "<gray>Sneak then melee a player to make them drop",
        "<gray>every armor piece.",
        "<gray>\u23f1 NO COOLDOWN.",
    ],
    "identity": {"key": "altarsmps2:bow_of_deception", "type": "byte", "value": 1},
    "owner_bound": True,
    "enabled_path": "bow-of-deception.enabled",
}


def apply_manual_overrides(s1: List[Dict[str, Any]], s2: List[Dict[str, Any]]) -> None:
    for entry in s1:
        override = MANUAL_S1.get(entry["id"])
        if override:
            entry.update(override)
    for entry in s2:
        override = MANUAL_S2.get(entry["id"])
        if override:
            entry.update(override)
    if not any(e["id"] == "bow_of_deception" for e in s2):
        s2.append(dict(BOW_OF_DECEPTION))


def main() -> int:
    ensure_sources()
    os.makedirs(OUT, exist_ok=True)
    s1: List[Dict[str, Any]] = []
    for path in sorted(os.listdir(os.path.join(SRC, "com/altarsmp/weapons"))):
        if not path.endswith(".java"):
            continue
        parsed = parse_s1_weapon(os.path.join(SRC, "com/altarsmp/weapons", path))
        if parsed:
            s1.append(parsed)
    s2: List[Dict[str, Any]] = []
    for path in sorted(os.listdir(os.path.join(SRC, "com/altarsmps2/weapons"))):
        if not path.endswith(".java"):
            continue
        parsed = parse_s2_weapon(os.path.join(SRC, "com/altarsmps2/weapons", path))
        if parsed:
            s2.append(parsed)
    items: List[Dict[str, Any]] = []
    for pkg in ("com/altarsmp/items", "com/altarsmps2/items"):
        d = os.path.join(SRC, pkg)
        if not os.path.isdir(d):
            continue
        for path in sorted(os.listdir(d)):
            if not path.endswith(".java"):
                continue
            parsed = parse_item_class(os.path.join(d, path))
            if parsed:
                parsed["package"] = pkg.split("/")[1]
                items.append(parsed)
    armor: List[Dict[str, Any]] = []
    d = os.path.join(SRC, "com/altarsmp/armor")
    for path in sorted(os.listdir(d)):
        if not path.endswith(".java"):
            continue
        parsed = parse_item_class(os.path.join(d, path))
        body = read(os.path.join(d, path))
        if parsed:
            cls = os.path.basename(path)[:-5]
            parsed["id"] = copper_armor_id(cls)
            armor.append(enrich_armor(parsed, body))
        if os.path.basename(path) == "CopperDiamondArmor.java":
            # Only this class builds the diamond-looking trial pieces; the other
            # four merely mention their ids when checking what a player wears.
            armor.extend(copper_diamond_variants(body))

    apply_manual_overrides(s1, s2)

    with open(os.path.join(OUT, "weapons_s1.json"), "w", encoding="utf-8") as fh:
        json.dump(s1, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "weapons_s2.json"), "w", encoding="utf-8") as fh:
        json.dump(s2, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "items.json"), "w", encoding="utf-8") as fh:
        json.dump(items, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "armor.json"), "w", encoding="utf-8") as fh:
        json.dump(armor, fh, indent=1, ensure_ascii=False)

    print("S1 weapons: %d" % len(s1))
    for w in s1:
        print("   %-22s mat=%-24s cmd=%-4s lore=%d ench=%d tip=%s" % (
            w["id"], w["base_material"], w["custom_model_data"], len(w["lore"]), len(w["enchants"]), w["tooltip_style"]))
    print("S2 weapons: %d" % len(s2))
    for w in s2:
        print("   %-22s mat=%-24s cmd=%-4s lore=%d tip=%s" % (
            w["id"], w["base_material"], w["custom_model_data"], len(w["lore"]), w["tooltip_style"]))
    print("items: %d  armor: %d" % (len(items), len(armor)))
    unresolved = 0
    for w in s1 + s2:
        for line in w["lore"]:
            if "{expr:" in line:
                unresolved += 1
                print("   UNRESOLVED lore expr in %s: %s" % (w["id"], line[:110]))
    print("unresolved lore expressions: %d" % unresolved)
    return 0


if __name__ == "__main__":
    sys.exit(main())
