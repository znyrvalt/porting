"""Discovery of the (base item, custom_model_data) pairs the *mod* can produce.

Three sources, all walked generically:

* ``altarsmp/content/*.json`` - every object anywhere in the tree that carries a
  base material and a model-data number (weapons, armour, items, and the altar
  display entries, which use their own ``material``/``cmd`` key names).
* ``ItemFactory.applyModelData`` call sites in the Java sources - the numbers
  that are written in code rather than declared in data (Knightfall's kill
  tiers, Dragonrend's void-clock pieces, Tidebreaker's thrown trident, the blue
  circle VFX).
* ``Displays.*`` call sites, so VFX-only item displays are accounted for.
"""

from __future__ import annotations

import json
import os
import re

CONTENT_DIR = "fabric-mod/src/main/resources/altarsmp/content"
JAVA_DIR = "fabric-mod/src/main/java"

MATERIAL_KEYS = ("base_material", "material", "base", "display_material")
CMD_KEYS = ("custom_model_data", "cmd", "model_data")


class ModPair:
    __slots__ = ("base", "cmd", "sources")

    def __init__(self, base, cmd):
        self.base = base
        self.cmd = cmd
        self.sources = []

    @property
    def key(self):
        return (self.base, self.cmd)

    def __repr__(self):
        return "ModPair(%s,%s)" % (self.base, self.cmd)


def _walk(node, path, hits):
    if isinstance(node, dict):
        material = None
        cmd = None
        for key in MATERIAL_KEYS:
            value = node.get(key)
            if isinstance(value, str) and value:
                material = value
                break
        for key in CMD_KEYS:
            value = node.get(key)
            if isinstance(value, int) and not isinstance(value, bool):
                cmd = value
                break
        if material and cmd is not None:
            label = node.get("id") or node.get("display") or node.get("class") or path
            hits.append((material, cmd, "content:%s:%s" % (path, label)))
        for key, value in node.items():
            _walk(value, "%s/%s" % (path, key) if path else key, hits)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            _walk(value, "%s[%d]" % (path, index), hits)


def content_pairs(root):
    hits = []
    directory = os.path.join(root, CONTENT_DIR)
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as fh:
            data = json.load(fh)
        _walk(data, name[:-5], hits)
    return hits


# ------------------------------------------------------------------ java side

APPLY = re.compile(r"applyModelData\s*\(\s*([A-Za-z0-9_.]+)\s*,\s*([^;]+?)\)\s*;")
NEW_STACK = re.compile(r"new\s+ItemStack\s*\(\s*(?:net\.minecraft\.world\.item\.)?Items\.([A-Z0-9_]+)\s*\)")
CONST_INT = re.compile(r"(?:static\s+final\s+int|final\s+int|int)\s+([A-Z_][A-Z0-9_]*)\s*=\s*(\d+)")
CONST_ARRAY = re.compile(r"int\[\]\s+([A-Z_][A-Z0-9_]*)\s*=\s*\{([^}]*)\}")
RETURN_INT = re.compile(r"return\s+(\d+)\s*;")


def _java_files(root):
    out = []
    for base, _dirs, files in os.walk(os.path.join(root, JAVA_DIR)):
        for name in files:
            if name.endswith(".java"):
                out.append(os.path.join(base, name))
    return sorted(out)


def java_pairs(root):
    """(material, cmd, source) triples discovered in the Java sources."""
    hits = []
    for path in _java_files(root):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if "applyModelData" not in text:
            continue
        rel = os.path.relpath(path, root)
        consts = {m.group(1): int(m.group(2)) for m in CONST_INT.finditer(text)}
        arrays = {}
        for match in CONST_ARRAY.finditer(text):
            values = [int(v) for v in re.findall(r"\d+", match.group(2))]
            arrays[match.group(1)] = values
        # the base item a stack got before the model data was written
        stacks = {m.group(1): m.group(2) for m in
                  re.finditer(r"ItemStack\s+(\w+)\s*=\s*new\s+ItemStack\s*\(\s*(?:net\.minecraft\.world\.item\.)?Items\.([A-Z0-9_]+)\s*\)", text)}
        for match in APPLY.finditer(text):
            var, expr = match.group(1), match.group(2).strip()
            line = text[:match.start()].count("\n") + 1
            base = stacks.get(var)
            values = []
            if expr.isdigit():
                values = [int(expr)]
            elif expr in consts:
                values = [consts[expr]]
            else:
                name = expr.split("(")[0].strip()
                for array_name, array_values in arrays.items():
                    if array_name in expr:
                        values = list(array_values)
                if not values:
                    # a helper such as cmdForKills(): take every literal it returns
                    body = _method_body(text, name)
                    if body:
                        values = sorted({int(v) for v in RETURN_INT.findall(body)})
            if not values:
                hits.append((base, None, "java:%s:%d:%s" % (rel, line, expr)))
                continue
            for value in values:
                hits.append((base, value, "java:%s:%d:%s" % (rel, line, expr)))
        # arrays of model numbers spawned through Displays (VFX pieces)
        for array_name, values in arrays.items():
            if "MODEL" in array_name:
                for value in values:
                    hits.append(("PAPER" if "PAPER" in text else None, value,
                                 "java:%s:%s" % (rel, array_name)))
    return hits


def _method_body(text, name):
    idx = text.find(" " + name + "(")
    if idx < 0:
        return None
    start = text.find("{", idx)
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i]
    return None


def display_call_sites(root):
    """Every ``Displays.<method>`` call site, for the VFX coverage note."""
    out = []
    pattern = re.compile(r"Displays\.(\w+)\s*\(")
    for path in _java_files(root):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if "Displays." not in text:
            continue
        rel = os.path.relpath(path, root)
        for match in pattern.finditer(text):
            line = text[:match.start()].count("\n") + 1
            out.append((rel, line, match.group(1)))
    return out


def class_materials(root):
    """``AncientBladeWeapon`` -> ``NETHERITE_SWORD``, from the content tables.

    Lets a code-side ``applyModelData`` call whose stack came from
    ``ItemFactory.weapon(id())`` inherit the base item the data file declares.
    """
    out = {}
    directory = os.path.join(root, CONTENT_DIR)
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as fh:
            data = json.load(fh)
        stack = [data]
        while stack:
            node = stack.pop()
            if isinstance(node, dict):
                cls = node.get("class") or node.get("gives_class")
                material = node.get("base_material") or node.get("material")
                if isinstance(cls, str) and isinstance(material, str):
                    out.setdefault(cls, material)
                stack.extend(node.values())
            elif isinstance(node, list):
                stack.extend(node)
    return out


def mod_pairs(root):
    """Merged, de-duplicated mod-side pair table."""
    pairs = {}
    unknown = []
    classes = class_materials(root)
    resolved = []
    for material, cmd, origin in content_pairs(root) + java_pairs(root):
        if material is None and origin.startswith("java:"):
            stem = os.path.basename(origin.split(":")[1])[:-5]
            material = classes.get(stem)
        resolved.append((material, cmd, origin))
    for material, cmd, origin in resolved:
        if material is None or cmd is None:
            unknown.append((material, cmd, origin))
            continue
        base = material.lower()
        key = (base, cmd)
        pair = pairs.get(key)
        if pair is None:
            pair = pairs[key] = ModPair(base, cmd)
        pair.sources.append(origin)
    return [pairs[k] for k in sorted(pairs)], unknown
