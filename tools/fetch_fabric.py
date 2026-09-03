#!/usr/bin/env python3
"""Fetch Fabric API sources for the exact target branch (26.2) from GitHub.

The sandbox cannot reach Maven/Modrinth, but api.github.com is reachable, so the
real event interfaces for MC 26.2 are read straight from FabricMC/fabric-api
instead of guessing signatures from older versions.
"""
import os, subprocess, sys

REPO = "FabricMC/fabric-api"
REF = os.environ.get("FABRIC_REF", "26.2")
CACHE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "audit", "fabric")


def fetch(rel):
    out = os.path.join(CACHE, REF, rel)
    if os.path.exists(out) and os.path.getsize(out) > 0:
        return out
    os.makedirs(os.path.dirname(out), exist_ok=True)
    url = "https://api.github.com/repos/%s/contents/%s?ref=%s" % (REPO, rel, REF)
    r = subprocess.run(["curl", "-sS", "--max-time", "90", "-H", "Accept: application/vnd.github.raw", url],
                       capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout or r.stdout.lstrip().startswith("{"):
        sys.stderr.write("FAILED %s\n" % rel)
        return None
    open(out, "w").write(r.stdout)
    return out


if __name__ == "__main__":
    for a in sys.argv[1:]:
        print(fetch(a) or ("MISS " + a))
