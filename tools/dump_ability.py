#!/usr/bin/env python3
"""Print the ability logic of a decompiled weapon class, skipping the
boilerplate getters that tools/extract_content.py already captured."""
import re, sys, pathlib

SKIP = {'getWeaponId','getWeaponName','getBaseMaterial','getCustomModelData','getTooltipStyleKey',
        'createWeapon','getConfigFields','withTooltip','nameWithTooltip','isThisWeapon','isHoldingThisWeapon',
        'hasWeaponInInventory','isCrazySlotsWeapon','resolveTooltipStyle','getLeapCooldown','getCageCooldown'}

src = pathlib.Path(sys.argv[1]).read_text(errors='replace')
lines = src.splitlines()
out=[]
i=0
skip_depth=None
brace=0
while i < len(lines):
    line=lines[i]
    m=re.match(r'^\s{3}(?:public|private|protected|static|final|abstract|synchronized| )[\w<>\[\],. ]+ (\w+)\(([^)]*)\)\s*\{', line)
    if m:
        name=m.group(1)
        if name in SKIP or (name == sys.argv[2] if len(sys.argv)>2 else False):
            # skip until matching brace closes
            depth=0
            while i < len(lines):
                depth += lines[i].count('{') - lines[i].count('}')
                i+=1
                if depth<=0: break
            continue
    if line.strip() and not line.startswith('import '):
        out.append(line)
    i+=1
# collapse
res=[]
for l in out:
    if l.strip()=='' :
        if res and res[-1].strip()=='': continue
    res.append(l)
print("\n".join(res))
