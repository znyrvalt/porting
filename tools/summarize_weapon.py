#!/usr/bin/env python3
"""Compact dump of a decompiled weapon class: signatures, config keys, strings,
Bukkit enum names and scheduler timings. Used to port each ability faithfully
without re-reading 1000+ lines of decompiler output."""
import re, sys, pathlib

src = pathlib.Path(sys.argv[1]).read_text(errors='replace')
src = re.sub(r'^import .*$', '', src, flags=re.M)
print("### FILE", sys.argv[1], len(src.splitlines()), "lines")
# fields
for m in re.finditer(r'^\s+(?:private|public|protected|final|static|\s)*[\w<>\[\],. ]+ (\w+) = ([^;]{0,90});', src, re.M):
    t = m.group(0).strip()
    if t.startswith(('private','public','protected','final','static')) and '(' not in t:
        print("FIELD:", t[:130])
print()
for m in re.finditer(r'^\s{3}(?:public|private|protected|static|final|abstract|synchronized| )*[\w<>\[\],. ]+ (\w+)\(([^)]*)\)\s*\{', src, re.M):
    name, params = m.group(1), m.group(2)
    print(f"METHOD: {name}({params[:90]})")
print()
keys = sorted(set(re.findall(r'getConfig\(\)\.\w+\("([^"]+)"', src)))
print("CONFIG KEYS:")
for k in keys: print("  ", k)
print()
sounds = sorted(set(re.findall(r'Sound\.(\w+)', src)))
print("SOUNDS:", ", ".join(sounds))
parts = sorted(set(re.findall(r'Particle\.(\w+)', src)))
print("PARTICLES:", ", ".join(parts))
effs = sorted(set(re.findall(r'PotionEffectType\.(\w+)', src)))
print("EFFECTS:", ", ".join(effs))
mats = sorted(set(re.findall(r'Material\.(\w+)', src)))
print("MATERIALS:", ", ".join(mats))
ents = sorted(set(re.findall(r'EntityType\.(\w+)', src)))
print("ENTITY_TYPES:", ", ".join(ents))
print()
strs = sorted(set(re.findall(r'"((?:[^"\\]|\\.){2,110})"', src)))
print("STRINGS (%d):" % len(strs))
for s in strs:
    if not s.startswith(('abilities.','curses.','totem.','cosmetics.','weapon-protection.','recipes.','altar')):
        print("  ", s)
print()
sched = sorted(set(re.findall(r'runTask(?:Later|Timer)?\([^;]{0,60}', src)))
print("SCHEDULER CALLS:", len(sched))
