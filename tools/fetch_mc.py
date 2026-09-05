#!/usr/bin/env python3
"""Fetch Mojang-mapped Minecraft sources from a public GitHub mirror.

The sandbox cannot reach Mojang/Maven, but the GitHub REST API is reachable, and
a full mojmap source tree for 1.21.11 (closest public proxy for MC 26.x) is
mirrored there. Cached under audit/mc/ (git-ignored) so verification is repeatable.
"""
import os, subprocess, sys, urllib.parse

REPO = "dawalishi0396/mc-1.21.11-"
REF = "d58cebda4affc844aaea77e28d0101b73f7bad55"
ROOT = "main/java/net/minecraft/"
CACHE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "audit", "mc")


def fetch(rel):
    out = os.path.join(CACHE, rel)
    if os.path.exists(out) and os.path.getsize(out) > 0:
        return out
    os.makedirs(os.path.dirname(out), exist_ok=True)
    url = ("https://api.github.com/repos/%s/contents/%s%s?ref=%s"
           % (REPO, ROOT, urllib.parse.quote(rel), REF))
    r = subprocess.run(["curl", "-sS", "--max-time", "90", "-H",
                        "Accept: application/vnd.github.raw", url],
                       capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout or r.stdout.lstrip().startswith("{"):
        sys.stderr.write("FAILED %s\n" % rel)
        return None
    with open(out, "w") as fh:
        fh.write(r.stdout)
    return out


if __name__ == "__main__":
    for a in sys.argv[1:]:
        p = fetch(a)
        print(p or ("MISS " + a))
