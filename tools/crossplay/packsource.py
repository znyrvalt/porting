"""Read-only view over the two shipped source archives.

The generator never unpacks anything to disk: it reads
``AltarSMP-ResourcePack.zip`` and ``AltarSMP.zip`` straight out of the
repository root so a build can never drift from the committed archives.  The
resource pack is the primary source; ``AltarSMP.zip`` is overlaid underneath it
(it carries the ``custom`` namespace armour, the equipment definitions and a few
vanilla item textures the resource pack leaves out).
"""

from __future__ import annotations

import json
import os
import posixpath
import zipfile

PRIMARY = "AltarSMP-ResourcePack.zip"
OVERLAY = "AltarSMP.zip"


def repo_root(start: str | None = None) -> str:
    here = os.path.abspath(start or __file__)
    while True:
        parent = os.path.dirname(here)
        if os.path.exists(os.path.join(here, PRIMARY)) and os.path.exists(os.path.join(here, OVERLAY)):
            return here
        if parent == here:
            raise RuntimeError("could not locate the repository root from %s" % __file__)
        here = parent


class PackSource:
    """The merged, read-only asset tree of both source archives."""

    def __init__(self, root: str | None = None):
        self.root = root or repo_root()
        self.zips = []
        self.origin = {}
        self.names = []
        for label, fname in (("resourcepack", PRIMARY), ("altarsmp", OVERLAY)):
            zf = zipfile.ZipFile(os.path.join(self.root, fname))
            self.zips.append((label, zf))
            for info in zf.infolist():
                if info.is_dir():
                    continue
                self.origin.setdefault(info.filename, (label, zf))
                self.names.append(info.filename)
        self.names = sorted(set(self.names))

    # ------------------------------------------------------------------ files

    def exists(self, name: str) -> bool:
        return name in self.origin

    def read(self, name: str) -> bytes:
        label, zf = self.origin[name]
        return zf.read(name)

    def read_json(self, name: str):
        return json.loads(self.read(name).decode("utf-8-sig"))

    def source_of(self, name: str) -> str:
        return self.origin[name][0]

    def listdir(self, prefix: str):
        prefix = prefix.rstrip("/") + "/"
        out = set()
        for name in self.names:
            if name.startswith(prefix):
                out.add(name)
        return sorted(out)

    # ------------------------------------------------- minecraft asset lookup

    @staticmethod
    def split(ref: str):
        if ":" in ref:
            ns, path = ref.split(":", 1)
        else:
            ns, path = "minecraft", ref
        return ns, path

    def asset(self, kind: str, ref: str, ext: str):
        """Return the archive path for ``assets/<ns>/<kind>/<path><ext>``."""
        ns, path = self.split(ref)
        name = posixpath.join("assets", ns, kind, path + ext)
        return name if self.exists(name) else None

    def model_path(self, ref: str):
        return self.asset("models", ref, ".json")

    def texture_path(self, ref: str):
        return self.asset("textures", ref, ".png")

    def item_definitions(self):
        """Top-level ``assets/<ns>/items/<id>.json`` files (no subdirectories).

        Subdirectories under ``items/`` in these packs hold model fragments that
        are referenced by the top-level definitions, not item definitions of
        their own, so they must not be walked as dispatch roots.
        """
        out = []
        for name in self.names:
            parts = name.split("/")
            if len(parts) == 4 and parts[0] == "assets" and parts[2] == "items" and name.endswith(".json"):
                out.append((parts[1], parts[3][:-5], name))
        return sorted(out)
