#!/usr/bin/env python3
"""Transliteration test for YamlLite#setValue.

The sandbox has no JDK, so this mirrors the Java writer line for line and runs it
over the config.yml the mod actually ships. It checks that an existing key is
rewritten in place with its comment kept, that a missing key lands under the
deepest parent that exists at the file's own indent width, and that a brand new
section is appended. Run: python3 tools/test_yaml_write.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "fabric-mod" / "src" / "main" / "resources" / "altarsmp" / "config" / "config.yml"


def find_key_colon(s: str) -> int:
    in_single = in_double = False
    for i, c in enumerate(s):
        if c == "'" and not in_double:
            in_single = not in_single
        elif c == '"' and not in_single:
            in_double = not in_double
        elif c == ":" and not in_single and not in_double:
            if i + 1 == len(s) or s[i + 1] in " \t":
                return i
    return -1


def unquote(s: str) -> str:
    if len(s) >= 2 and s[0] == s[-1] and s[0] in "\"'":
        return s[1:-1]
    return s


def scalar_text(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, int):
        return str(value)
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def trailing_comment(rest: str) -> str:
    in_single = in_double = False
    for i, c in enumerate(rest):
        if c == "'" and not in_double:
            in_single = not in_single
        elif c == '"' and not in_single:
            in_double = not in_double
        elif c == "#" and not in_single and not in_double and i > 0 and rest[i - 1].isspace():
            return rest[i - 1:]
    return ""


def indent_unit(lines) -> int:
    unit = None
    for line in lines:
        trimmed = line.lstrip()
        if not trimmed or trimmed.startswith("#"):
            continue
        indent = len(line) - len(trimmed)
        if indent > 0:
            unit = indent if unit is None else min(unit, indent)
    return 2 if unit is None else unit


def with_value(line: str, value) -> str:
    trimmed = line.lstrip()
    leading = len(line) - len(trimmed)
    colon = find_key_colon(trimmed)
    rest = trimmed[colon + 1:]
    return " " * leading + trimmed[:colon + 1] + " " + scalar_text(value) + trailing_comment(rest)


def set_value(content: str, path: str, value) -> str:
    parts = path.split(".")
    newline = "\r\n" if "\r\n" in content else "\n"
    lines = content.split("\r\n" if "\r\n" in content else "\n")
    unit = indent_unit(lines)

    level_indent = [0] * len(parts)
    depth = best_depth = 0
    best_end = -1
    best_indent = -1

    for i, line in enumerate(lines):
        trimmed = line.strip()
        if not trimmed or trimmed.startswith("#") or trimmed.startswith("- "):
            continue
        indent = len(line) - len(line.lstrip())
        colon = find_key_colon(trimmed)
        if colon <= 0:
            continue
        key = unquote(trimmed[:colon].strip())
        while depth > 0 and indent <= level_indent[depth - 1]:
            depth -= 1
        if depth < len(parts) and key == parts[depth]:
            level_indent[depth] = indent
            depth += 1
            if depth == len(parts):
                lines[i] = with_value(line, value)
                return newline.join(lines)
            if depth > best_depth:
                best_depth, best_indent, best_end = depth, level_indent[depth - 1], i
        if depth >= best_depth and best_depth > 0:
            best_end = i

    indent = 0 if best_depth == 0 else best_indent + unit
    added = []
    for i in range(best_depth, len(parts)):
        pad = " " * max(0, indent + unit * (i - best_depth))
        added.append(pad + parts[i] + ": " + scalar_text(value) if i == len(parts) - 1 else pad + parts[i] + ":")
    at = len(lines) if best_end < 0 else best_end + 1
    if 0 < at <= len(lines) and lines[at - 1].strip() and best_depth == 0:
        added.insert(0, "")
    lines[at:at] = added
    return newline.join(lines)


def show(text: str, path: str, context: int = 3) -> str:
    lines = text.split("\n")
    leaf = path.split(".")[-1]
    for i, line in enumerate(lines):
        if line.strip().startswith(leaf + ":"):
            return "\n".join(lines[max(0, i - context):i + context + 1])
    return f"!! {leaf} not found"


def main() -> None:
    original = CONFIG.read_text()
    cases = [
        ("abilities.hyperion.holy_lance_cooldown", 45),
        ("abilities.hyperion.holy_lance_damage", 12.5),
        ("weapon-protection.enabled", False),
        ("abilities.palecrossbow.pale_shot.gravity", 0.0025),
        ("abilities.hyperion.brand_new_value", 7),
        ("brand-new-section.nested.value", 1),
    ]
    text = original
    for path, value in cases:
        text = set_value(text, path, value)
        print(f"--- {path} = {value!r}")
        print(show(text, path))
        print()
    # every original key line must still be there
    lost = [l for l in original.split("\n") if l.strip() and not l.strip().startswith("#")
            and l not in text.split("\n")]
    print("lines replaced (expected one per existing-key case):")
    for line in lost:
        print("  -", line)
    Path("/tmp/config-edited.yml").write_text(text)
    print("\nfull result written to /tmp/config-edited.yml")


if __name__ == "__main__":
    main()
