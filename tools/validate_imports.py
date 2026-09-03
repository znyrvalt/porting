#!/usr/bin/env python3
"""Structural validator for the Fabric port.

There is no JDK in this sandbox, so the port cannot be compiled here. This script
is the next best thing: it walks every Java file, collects both the `import`
statements and the fully-qualified names used inline, and checks each one against

  * the project's own source tree              (com.altarsmp.fabric.*)
  * the Mojang-mapped Minecraft sources cloned
    into audit/mc-src                          (net.minecraft.*)
  * the Fabric API sources cloned into
    audit/fabric-src, when present             (net.fabricmc.*)

Nested classes (`TickScheduler.Task`, `Display$ItemDisplay`) are resolved to their
outer file. Anything unresolved is printed with file:line so it can be fixed
before the project is handed to a real compiler.

Usage: python3 tools/validate_imports.py [--verbose]
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "fabric-mod", "src", "main", "java")
CLIENT_SRC = os.path.join(ROOT, "fabric-mod", "src", "client", "java")
AUDIT = os.path.join(ROOT, "audit")
MC_SRC = os.path.join(AUDIT, "mc-src", "main", "java")
FABRIC_SRC = os.path.join(AUDIT, "fabric-src")

# Packages provided by the JDK or by libraries that ship with the toolchain and
# therefore cannot be checked against a source tree.
SKIP_PREFIXES = (
    "java.", "javax.", "jdk.", "sun.",
    "org.slf4j.", "com.google.", "com.mojang.brigadier.", "it.unimi.",
    "org.apache.", "org.spongepowered.", "com.electronwill.", "org.jetbrains.",
)

IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?([\w.$]+)\s*;")
# Fully-qualified names written inline, e.g. `new net.minecraft.world.phys.Vec3(...)`.
INLINE_RE = re.compile(r"\b((?:net\.minecraft|net\.fabricmc|com\.altarsmp)\.[\w.]+)")


def project_classes():
    """Map every project class (including nested ones) to its file."""
    known = {}
    for base in (SRC, CLIENT_SRC):
        for dirpath, _dirs, files in os.walk(base):
            for name in files:
                if not name.endswith(".java"):
                    continue
                path = os.path.join(dirpath, name)
                rel = os.path.relpath(path, base)
                fqn = rel[:-5].replace(os.sep, ".")
                known[fqn] = rel
                # Nested classes declared inside the file: `class Foo`, `record Foo`,
                # `interface Foo`, `enum Foo`.
                try:
                    body = open(path, encoding="utf-8", errors="replace").read()
                except OSError:
                    continue
                for nested in re.findall(
                        r"\b(?:class|record|interface|enum)\s+([A-Z]\w*)", body):
                    known.setdefault(fqn + "." + nested, rel)
    return known


def mc_classes():
    known = set()
    for dirpath, _dirs, files in os.walk(MC_SRC):
        for name in files:
            if name.endswith(".java"):
                rel = os.path.relpath(os.path.join(dirpath, name), MC_SRC)
                known.add(rel[:-5].replace(os.sep, "."))
    return known


def fabric_classes():
    known = set()
    if not os.path.isdir(FABRIC_SRC):
        return known
    for dirpath, _dirs, files in os.walk(FABRIC_SRC):
        if os.sep + ".git" in dirpath:
            continue
        for name in files:
            if name.endswith(".java"):
                rel = os.path.relpath(os.path.join(dirpath, name), FABRIC_SRC)
                fqn = rel[:-5].replace(os.sep, ".")
                # fabric-api keeps several source roots; keep the last package-ish tail
                known.add(fqn)
                if ".java." in fqn:
                    known.add(fqn.split(".java.", 1)[1])
    return known


def outer(name):
    """Strip nested-class segments until something that could be a file remains."""
    parts = name.replace("$", ".").split(".")
    for cut in range(len(parts), 0, -1):
        candidate = ".".join(parts[:cut])
        if not candidate.split(".")[-1][:1].isupper():
            continue
        yield candidate


def check(name, project, mc, fabric):
    """Return (ok, note). `note` explains a skip or a near miss."""
    if name.startswith(SKIP_PREFIXES):
        return True, "skip"
    if name.startswith("com.altarsmp.fabric"):
        for candidate in outer(name):
            if candidate in project:
                return True, ""
        return False, "project"
    if name.startswith("net.minecraft"):
        for candidate in outer(name):
            if candidate in mc:
                return True, ""
        return False, "minecraft"
    if name.startswith("net.fabricmc"):
        if not fabric:
            return True, "fabric sources not cloned (audit/fabric-src missing)"
        for candidate in outer(name):
            if candidate in fabric or any(f.endswith(candidate) for f in fabric):
                return True, ""
        return False, "fabric"
    return True, "skip"


def main():
    verbose = "--verbose" in sys.argv
    project = project_classes()
    mc = mc_classes()
    fabric = fabric_classes()
    print("project classes: %d   minecraft classes: %d   fabric classes: %d"
          % (len(project), len(mc), len(fabric)))
    if not mc:
        print("WARNING: %s is missing - clone it with\n"
              "  git clone --depth 1 https://github.com/dawalishi0396/mc-1.21.11-.git %s"
              % (MC_SRC, MC_SRC))

    missing = []
    skipped = {}
    files = []
    for base in (SRC, CLIENT_SRC):
        for dirpath, _dirs, names in os.walk(base):
            for name in sorted(names):
                if name.endswith(".java"):
                    files.append(os.path.join(dirpath, name))

    for path in sorted(files):
        rel = os.path.relpath(path, ROOT)
        own_package = None
        for lineno, line in enumerate(open(path, encoding="utf-8", errors="replace"), 1):
            if line.startswith("package "):
                own_package = line.strip()[8:-1].strip()
                continue
            match = IMPORT_RE.match(line)
            names = []
            if match:
                imported = match.group(2)
                if match.group(1):  # import static a.b.C.member;
                    imported = imported.rsplit(".", 1)[0]
                names.append(imported)
            else:
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                names.extend(INLINE_RE.findall(line))
            for name in names:
                if name.endswith(".*"):
                    continue
                ok, note = check(name, project, mc, fabric)
                if not ok:
                    missing.append((rel, lineno, name, note))
                elif note and verbose:
                    skipped.setdefault(note, set()).add(name)
        # Same-package references need no import, so nothing to check there.
        del own_package

    for note, names in sorted(skipped.items()):
        print("note: %s (%d names)" % (note, len(names)))
    if missing:
        print("\nMISSING %d:" % len(missing))
        for rel, lineno, name, note in missing:
            print("  %s:%d  %s   [%s]" % (rel, lineno, name, note))
        return 1
    print("\nOK: every import and inline fully-qualified name resolves (%d files)"
          % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
