#!/usr/bin/env python3
"""Builds the editable-stat tables for /legendaryconfig from the decompiled plugin.

Every weapon, armour piece and the global-settings block in the original plugin
declared its editable values through a.getConfigFields()/configFields(), using the
sealed field interface a.c: two args = a toggle, four = a whole number with a
range, five = a decimal with a range and a step. This reads those declarations
straight out of the sources so the port's tables are the plugin's tables.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "audit" / "sources"
OUT = ROOT / "fabric-mod" / "src" / "main" / "java" / "com" / "altarsmp" / "fabric" / "config" / "ConfigFields.java"

# Upstream class -> the content id the port's catalogue uses.
CONTENT_IDS = {
    "HyperionWeapon": "hyperion", "NightpiercerWeapon": "nightpiercer", "PaleCrossbowWeapon": "palecrossbow",
    "CutlassWeapon": "cutlass", "BoneBladeWeapon": "boneblade", "ShadowBladeWeapon": "shadowblade",
    "EarthGauntletWeapon": "earthgauntlet", "VulcansCrossbowWeapon": "vulcanscrossbow",
    "BloodlustWeapon": "bloodlust", "EchoWeapon": "echo", "EclipseSwordWeapon": "eclipsesword",
    "KnightfallWeapon": "knightfall", "NukeLauncherWeapon": "nukelauncher",
    "PaladinBattleAxeWeapon": "paladinbattleaxe", "PureBladeWeapon": "pureblade", "StrikerWeapon": "striker",
    "WandOfIllusionWeapon": "wandofillusion", "WindweaverWeapon": "windweaver",
    "WitherboneWeapon": "witherbone", "CrazySlotsWeapon": "crazyslots", "FrostScytheWeapon": "frostscythe",
    "ContagionSignalWeapon": "contagionsignal", "MinorCrazySlotsWeapon": "minorcrazyslots",
    "CopperHelmet": "copperhelmet", "CopperChestplate": "copperchestplate",
    "CopperLeggings": "copperleggings", "CopperBoots": "copperboots", "CopperPickaxe": "copperpickaxe",
    "OmenWeapon": "omen", "AncientBladeWeapon": "ancientblade", "WitherSymbioteWeapon": "withersymbiote",
    "TidebreakerWeapon": "tidebreaker", "DragonrendWeapon": "dragonrend",
    "BowOfDeceptionWeapon": "bowofdeception", "BlackGhastSaddle": "blackghastsaddle",
    "SoulInABottle": "soulinabottle", "FragmentOfTheSea": "fragmentofthesea", "DragonHeart": "dragonheart",
    "AmethystPickaxe": "amethystpickaxe", "AmethystAxe": "amethystaxe", "WardenHeart": "wardenheart",
}

# A field factory call: c.a(...), a.c.a(...) in season 1 and v.a(...) in season 2.
CALL = re.compile(r'\b\w+(?:\.\w+)?\.a\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*((?:,\s*[^)]+?)?)\)', re.S)


def fields_of(body: str):
    """Turns one getConfigFields() body into (label, path, kind, args) tuples."""
    out = []
    for label, path, rest in CALL.findall(body):
        nums = [n.strip() for n in rest.split(",") if n.strip()]
        label = label.replace('\\"', '"')
        if not nums:
            out.append((label, path, "toggle", []))
        elif len(nums) == 2:
            out.append((label, path, "integer", nums))
        elif len(nums) == 3:
            out.append((label, path, "decimal", nums))
        else:
            raise SystemExit(f"unrecognised field arity in {path}: {nums}")
    return out


def method_body(text: str, name: str):
    """The brace-balanced body of the first `List<field> <name>()` method.

    The field type is `c` in season 1 (sometimes written `a.c`) and `v` in season 2.
    """
    match = re.search(r'List<\w+(?:\.\w+)?>\s+' + re.escape(name) + r'\s*\(\s*\)\s*\{', text)
    if not match:
        return None
    depth, start = 1, match.end()
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i]
    return None


def java_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def field_source(label, path, kind, nums) -> str:
    if kind == "toggle":
        return f"Field.toggle({java_string(label)}, {java_string(path)})"
    if kind == "integer":
        return f"Field.integer({java_string(label)}, {java_string(path)}, {nums[0]}, {nums[1]})"
    return (f"Field.decimal({java_string(label)}, {java_string(path)}, "
            f"{nums[0].rstrip('Ff')}F, {nums[1].rstrip('Ff')}F, {nums[2].rstrip('Ff')}F)")


def main() -> None:
    tables: dict[str, list] = {}
    order: list[str] = []
    for java in sorted(SOURCES.rglob("*.java")):
        text = java.read_text(errors="replace")
        stem = java.stem
        if stem == "LegendaryConfigCommand" and "globalsettings" not in tables:
            # The season 1 command also carries the global-settings block.
            global_body = method_body(text, "globalSettingsFields")
            if global_body:
                tables["globalsettings"] = fields_of(global_body)
                order.append("globalsettings")
        for method in ("getConfigFields", "configFields"):
            body = method_body(text, method)
            if not body:
                continue
            found = fields_of(body)
            if not found:
                continue
            content = CONTENT_IDS.get(stem)
            if content is None:
                print(f"!! no content id for {java} ({len(found)} fields)")
                continue
            if content in tables:
                continue
            tables[content] = found
            order.append(content)

    # The season 1 order is the order LegendaryConfigCommand#entries used.
    s1_order = ["hyperion", "nightpiercer", "palecrossbow", "cutlass", "boneblade", "shadowblade",
                "earthgauntlet", "vulcanscrossbow", "bloodlust", "echo", "eclipsesword", "knightfall",
                "nukelauncher", "paladinbattleaxe", "pureblade", "striker", "wandofillusion", "windweaver",
                "witherbone", "crazyslots", "copperhelmet", "copperchestplate", "copperleggings",
                "copperboots", "copperpickaxe"]
    s2_order = ["omen", "ancientblade", "withersymbiote", "tidebreaker", "dragonrend"]
    ordered = [k for k in s1_order if k in tables] + [k for k in s2_order if k in tables]
    ordered += [k for k in order if k not in ordered]

    lines = []
    lines.append("package com.altarsmp.fabric.config;")
    lines.append("")
    lines.append("import java.util.LinkedHashMap;")
    lines.append("import java.util.List;")
    lines.append("import java.util.Map;")
    lines.append("import java.util.Set;")
    lines.append("")
    lines.append("/**")
    lines.append(" * The editable-stat tables behind {@code /legendaryconfig} and {@code /legendaryconfig2}.")
    lines.append(" *")
    lines.append(" * <p>Each weapon and armour piece in the plugin declared the values an operator could")
    lines.append(" * change through {@code BaseWeapon#getConfigFields()}; the season 1 command added a")
    lines.append(" * global block of its own. Those declarations are reproduced here verbatim - labels,")
    lines.append(" * config paths, ranges and steps - by {@code tools/extract_config_fields.py}, so the")
    lines.append(" * editor offers exactly the numbers the plugin offered, bounded the way it bounded")
    lines.append(" * them.")
    lines.append(" */")
    lines.append("public final class ConfigFields {")
    lines.append("\t/** What kind of value a field holds, which decides how an edit is parsed. */")
    lines.append("\tpublic enum Kind { TOGGLE, INTEGER, DECIMAL }")
    lines.append("")
    lines.append("\t/**")
    lines.append("\t * One editable value: {@code a.c} in the plugin, whose three variants were a toggle,")
    lines.append("\t * a whole number with a range, and a decimal with a range and a step.")
    lines.append("\t */")
    lines.append("\tpublic record Field(String label, String path, Kind kind, double min, double max, double step) {")
    lines.append("\t\tpublic static Field toggle(String label, String path) {")
    lines.append("\t\t\treturn new Field(label, path, Kind.TOGGLE, 0.0D, 1.0D, 1.0D);")
    lines.append("\t\t}")
    lines.append("")
    lines.append("\t\tpublic static Field integer(String label, String path, int min, int max) {")
    lines.append("\t\t\treturn new Field(label, path, Kind.INTEGER, min, max, 1.0D);")
    lines.append("\t\t}")
    lines.append("")
    lines.append("\t\tpublic static Field decimal(String label, String path, float min, float max, float step) {")
    lines.append("\t\t\treturn new Field(label, path, Kind.DECIMAL, min, max, step);")
    lines.append("\t\t}")
    lines.append("")
    lines.append("\t\t/** The value's default when the config has no entry: the range floor, as in the plugin. */")
    lines.append("\t\tpublic double defaultValue() {")
    lines.append("\t\t\treturn this.kind == Kind.TOGGLE ? 0.0D : this.min;")
    lines.append("\t\t}")
    lines.append("\t}")
    lines.append("")
    lines.append("\t/** The global block's id; it is not a catalogue item. */")
    lines.append("\tpublic static final String GLOBAL = \"globalsettings\";")
    lines.append("")
    lines.append("\tprivate static final Map<String, List<Field>> TABLES = tables();")
    lines.append("")
    lines.append("\tprivate ConfigFields() {}")
    lines.append("")
    lines.append("\tprivate static Map<String, List<Field>> tables() {")
    lines.append("\t\tMap<String, List<Field>> tables = new LinkedHashMap<>();")
    for key in ordered:
        lines.append(f"\t\ttables.put({java_string(key)}, List.of(")
        entries = tables[key]
        for i, entry in enumerate(entries):
            comma = "," if i < len(entries) - 1 else ""
            lines.append(f"\t\t\t\t{field_source(*entry)}{comma}")
        lines.append("\t\t));")
    lines.append("\t\treturn tables;")
    lines.append("\t}")
    lines.append("")
    lines.append("\t/** Every content id that has editable stats, in the order the plugin listed them. */")
    lines.append("\tpublic static Set<String> contentIds() {")
    lines.append("\t\treturn TABLES.keySet();")
    lines.append("\t}")
    lines.append("")
    lines.append("\t/** The editable stats of one catalogue entry; empty when it has none. */")
    lines.append("\tpublic static List<Field> forContent(String contentId) {")
    lines.append("\t\treturn TABLES.getOrDefault(contentId, List.of());")
    lines.append("\t}")
    lines.append("")
    lines.append("\t/** Season 2's browser only offered its five weapons. */")
    lines.append("\tpublic static List<String> seasonTwoIds() {")
    lines.append("\t\treturn List.of(\"omen\", \"ancientblade\", \"withersymbiote\", \"tidebreaker\", \"dragonrend\");")
    lines.append("\t}")
    lines.append("")
    lines.append("\t/** Season 1's browser: everything except the global block and season 2's weapons. */")
    lines.append("\tpublic static List<String> seasonOneIds() {")
    lines.append("\t\tList<String> ids = new java.util.ArrayList<>(TABLES.keySet());")
    lines.append("\t\tids.removeAll(seasonTwoIds());")
    lines.append("\t\tids.remove(GLOBAL);")
    lines.append("\t\treturn ids;")
    lines.append("\t}")
    lines.append("}")
    lines.append("")

    OUT.write_text("\n".join(lines))
    total = sum(len(v) for v in tables.values())
    print(f"wrote {OUT.relative_to(ROOT)}: {len(tables)} tables, {total} fields")
    for key in ordered:
        print(f"  {key}: {len(tables[key])}")


if __name__ == "__main__":
    main()
