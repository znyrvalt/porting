#!/usr/bin/env python3
"""Writes docs/commands.md: every plugin.yml command against what the port registers.

The original plugin declared its command surface in plugin.yml and pointed each name
at a CommandExecutor. This reads that declaration and cross-checks it against
CommandRegistrar, so the table says plainly which names exist in the port, which
permission node each one carried, and what the executor became. Run:
python3 tools/generate_command_doc.py
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_YML = ROOT / "audit" / "sources" / "plugin.yml"
REGISTRAR = ROOT / "fabric-mod" / "src" / "main" / "java" / "com" / "altarsmp" / "fabric" / "command" / "CommandRegistrar.java"
OUT = ROOT / "docs" / "commands.md"

# Where each name's behaviour lives in the port, for the notes column.
NOTES = {
    "altarsmp": "help, reload, the content lists and the roulette-pool editor",
    "altartooltip": "writes the `tooltip_display` component on the held item",
    "lockaltars": "AltarManager lock flag, read by every altar interaction",
    "recipes": "the season 2 recipe browser (RecipesGui)",
    "legendaries": "the item browser, season 1 page (LegendariesGui)",
    "legendaries2": "the item browser, season 2 page",
    "legendaryconfig": "the stat editor plus `set <entry> <path> <value>` (ConfigFields)",
    "legendaryconfig2": "the season 2 stat editor",
    "spawnaltarrandom": "**not ported yet** - see docs/limitations.md",
    "copperdiamondarmor": "takes helmet|chestplate|leggings|boots|all",
    "minorcrazyslots": "takes a player or `addpool`",
    "knightfallmax": "runs the held blade through KnightfallWeapon#applyKills",
    "setkills": "bloodlust 0-5, knightfall 0-10",
    "setkillss": "writes the Ancient Blade counter on the held blade and in the record",
    "setbloodlust": "stored kill count",
    "bloodkills": "reads the stored count and prints the unlock table",
    "tabcolor": "scoreboard team per colour (`asmp_tc_<colour>`), the vanilla mechanism for a tinted tab name",
    "tc": "alias of tabcolor",
    "blueparticle": "the `a/z.java` blue circle: paper with custom model data 1, tagged `altarsmps2_vfx`",
    "destroyaltars": "radius, `all` or `allworlds` through AltarManager#sweep",
    "pale": "wakes the spreading pale system; it does not pale everyone",
    "altarconfig": "prints the config.yml pointer - the plugin shipped no GUI class for it",
    "asmpconfig": "alias of altarconfig",
    "altarsmpconfig": "alias of altarconfig",
    "altarsmps2reload": "reloads both documents",
    "s2reload": "alias of altarsmps2reload",
    "copperfragment": "the trial's own give path: the fragment leaks coordinates",
    "bingo": "BingoEvent board (BingoTasksMenu)",
    "coppertrial": "CopperTrialService",
    "chestplatetrial": "the chestplate trial's shard counter",
    "bloodmoon": "BloodMoonManager, which drives the whole event rather than the clock",
    "deathmatch": "DeathmatchManager",
    "nukezone": "NukeZoneManager",
    "banzone": "BanZoneSystem",
    "contagionstop": "ContagionSignalManager",
    "controls": "CommandControlsManager",
    "ability1": "ability bus, slot 1",
    "ability2": "ability bus, slot 2",
    "cooldown": "CooldownManager",
    "trust": "per-player trust list",
    "untrust": "per-player trust list",
    "trustlist": "per-player trust list",
    "altar": "AltarRegistry + AltarManager#createAltar",
    "altarspawn": "alias of altar",
    "altars2": "the season 2 altar list",
    "lock": "WeaponProtection lock on the held item",
    "unlock": "WeaponProtection lock on the held item",
    "pvp": "DeathmatchManager pvp toggle",
    "hotpotato": "the hot potato event",
    "vampire": "FactionManager",
    "human": "FactionManager",
}


def parse_plugin_yml(text: str):
    lines = text.split("\n")
    start = next(i for i, line in enumerate(lines) if line.rstrip() == "commands:")
    commands, current = {}, None
    for line in lines[start + 1:]:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        head = re.match(r"^  ([a-z0-9_]+):\s*$", line)
        if head:
            current = head.group(1)
            commands[current] = {}
            continue
        field = re.match(r"^    ([a-z-]+):\s*(.*)$", line)
        if field and current:
            commands[current][field.group(1)] = field.group(2).strip().strip('"')
        elif re.match(r"^[a-z]", line):
            break
    return commands


def main() -> None:
    commands = parse_plugin_yml(PLUGIN_YML.read_text())
    registrar = REGISTRAR.read_text()

    rows, missing = [], []
    for name, meta in commands.items():
        present = f'"{name}"' in registrar
        if not present:
            missing.append(name)
        permission = meta.get("permission", "-")
        usage = meta.get("usage", "").replace("<command>", name)
        description = meta.get("description", "")
        note = NOTES.get(name, "")
        rows.append((name, description, permission, usage, "yes" if present else "**no**", note))

    out = ["# Commands", "",
           f"The plugin declared {len(commands)} commands in `plugin.yml`; the port registers "
           f"{len(commands) - len(missing)} of them in `CommandRegistrar`, one Brigadier tree per name.",
           "",
           "Two mechanical differences apply to every row and are not repeated in the table:",
           "",
           "- **Permissions.** Every `altarsmp.*` / `altarsmps2.*` node in the plugin defaulted to op. "
           "Vanilla has no permission nodes, so admin commands require `Permissions.COMMANDS_GAMEMASTER` "
           "(level 2, the level op grants) and the rest stay open to every player.",
           "- **Tab completion.** The plugin's `TabCompleter` lists became Brigadier `suggests` lambdas.",
           "",
           "Commands are admin interfaces only: every one of them calls the same game-side code the "
           "plugin called, and none of them returns success without doing the work.",
           "",
           "| Command | Plugin description | Permission node | Usage | Ported | What it calls |",
           "| --- | --- | --- | --- | --- | --- |"]
    for name, description, permission, usage, present, note in rows:
        out.append(f"| `/{name}` | {description} | `{permission}` | `{usage or f'/{name}'}` | {present} | {note} |")
    if missing:
        out += ["", "## Not registered", "",
                "These names are declared in `plugin.yml` and have no Brigadier tree in the port:", ""]
        out += [f"- `/{name}`" for name in missing]
    out.append("")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out))
    print(f"wrote {OUT.relative_to(ROOT)}: {len(rows)} commands, {len(missing)} unregistered -> {missing}")


if __name__ == "__main__":
    main()
