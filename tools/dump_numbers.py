#!/usr/bin/env python3
"""Numeric/logic fingerprint of a weapon class: config calls with defaults,
effects, particles, sounds, damage, velocity, scheduler delays, boss bars."""
import re, sys, pathlib

PATTERNS = [
    ('CFG',   r'getConfig\(\)\.\w+\([^;]*?\)'),
    ('EFFECT',r'new PotionEffect\([^;]*?\)\s*\)?'),
    ('PART',  r'spawnParticle\([^;]*?\);'),
    ('SOUND', r'playSound\([^;]*?\);'),
    ('DMG',   r'(?:applyTrueDamage|\.damage\(|setHealth|setAbsorptionAmount)[^;]*?;'),
    ('VEL',   r'(?:setVelocity|getDirection\(\)|multiply\()[^;]*?;'),
    ('SCHED', r'runTask(?:Later|Timer)?\([^;]*?(?:,\s*[\d.]+L?\s*[,)])'),
    ('BAR',   r'createBossBar\([^;]*?\);'),
    ('TITLE', r'(?:sendTitle|showTitle|Title\.title)\([^;]*?;'),
    ('MSG',   r'sendMessage\((?:mm|a)\.deserialize\("([^"]{1,140})"\)?[^;]*\);'),
    ('SPAWN', r'spawn\([^;]{0,90};'),
    ('ENTITY',r'(?:setGravity|setInvulnerable|setGlowing|setNoDamageTicks|addPotionEffect|setFireTicks|setCustomName|setSilent)\([^;]{0,80}\);'),
]

def dump(path):
    src = pathlib.Path(path).read_text(errors='replace')
    print('='*70)
    print('##', pathlib.Path(path).name)
    # method map for context
    for m in re.finditer(r'^\s{3}(?:public|private|protected|static|final| )*[\w<>\[\],. ]+ (\w+)\(([^)]*)\)\s*\{', src, re.M):
        print(f"  def {m.group(1)}({m.group(2)[:70]})")
    flat = re.sub(r'\s+', ' ', src)
    for label, pat in PATTERNS:
        hits=[]
        for m in re.finditer(pat, flat):
            t = re.sub(r'this\.plugin\.|plugin\.|var\d+\.', '', m.group(0))
            t = re.sub(r'\s+', ' ', t).strip()
            if t not in hits: hits.append(t)
        if hits:
            print(f"  [{label}]")
            for h in hits[:60]:
                print('     ', h[:190])

for p in sys.argv[1:]:
    dump(p)
