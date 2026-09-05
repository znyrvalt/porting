"""Access to the authoritative Java asset archives for the crossplay tooling.

Inputs (both committed at the repository root, per docs/asset-audit.md):

- ``AltarSMP-ResourcePack.zip`` - the authoritative asset input: dispatch
  definitions, models, textures and the ability tooltip artwork.
- ``Altar_SMPS1-2-sources-FRESH.jar`` - the original plugin sources, used only
  to *document* where each reconciled custom_model_data value came from; the
  generator never extracts assets from it.

Everything here is read-only and deterministic: same archives in, same data out.
"""

import json
import os
import re
import zipfile

from pngio import decode_png, PngError

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACK_ZIP = os.path.join(REPO_ROOT, 'AltarSMP-ResourcePack.zip')
CONTENT_DIR = os.path.join(
    REPO_ROOT, 'fabric-mod', 'src', 'main', 'resources', 'altarsmp', 'content')
MOD_ICON = os.path.join(
    REPO_ROOT, 'fabric-mod', 'src', 'main', 'resources', 'assets', 'altarsmp', 'icon.png')

BUILTIN_FLAT_PARENTS = ('item/generated', 'minecraft:item/generated',
                        'item/handheld', 'minecraft:item/handheld',
                        'builtin/generated', 'minecraft:builtin/generated')


def norm_type(t):
    """'minecraft:range_dispatch' -> 'range_dispatch' (entries mix both spellings)."""
    return (t or '').split(':')[-1]


def camel_to_snake(name):
    """HyperionShard -> hyperion_shard (used where content entries have no id)."""
    out = []
    for i, ch in enumerate(name):
        if ch.isupper() and out and (out[-1] != '_' and
                                     (not out[-1].isupper() or
                                      (i + 1 < len(name) and name[i + 1].islower()))):
            out.append('_')
        out.append(ch.lower())
    return ''.join(out)


class ModelChain(object):
    """The resolved parent chain of one Java item model."""

    def __init__(self, model_id, textures, elements, texture_size, gui, parents_missing):
        self.model_id = model_id
        self.textures = textures          # merged texture map, parent-first fill
        self.elements = elements          # list of element dicts (nearest declarer)
        self.texture_size = texture_size  # declared by the element-owning model
        self.gui = gui                    # display.gui block or None
        self.parents_missing = parents_missing

    @property
    def is_3d(self):
        return self.elements is not None


class JavaPack(object):
    """Read-only view over AltarSMP-ResourcePack.zip."""

    def __init__(self, path=PACK_ZIP):
        self.path = path
        self.z = zipfile.ZipFile(path)
        self.names = set(self.z.namelist())
        self._models = {}
        self._textures = {}
        self._mcmeta = {}

    # ------------------------------------------------------------- models

    def load_model(self, model_id):
        """Parse assets/<ns>/models/<path>.json; None if the pack lacks it."""
        if model_id in self._models:
            return self._models[model_id]
        ns, _, path = model_id.partition(':')
        if not path:
            ns, path = 'minecraft', model_id
        model = None
        candidates = ['assets/%s/models/%s.json' % (ns, path),
                      'assets/minecraft/models/%s.json' % path]
        for cand in candidates:
            if cand in self.names:
                model = json.loads(self.z.read(cand))
                model['_ns'] = cand.split('/')[1]
                model['_path'] = cand
                break
        self._models[model_id] = model
        return model

    def chain(self, model_id):
        """Walk the parent chain: merge textures, find elements/texture_size/gui."""
        textures = {}
        elements = None
        texture_size = None
        gui = None
        elements_owner = None
        missing = []
        cur, seen = model_id, 0
        while cur and seen < 12:
            model = self.load_model(cur)
            if model is None:
                missing.append(cur)
                break
            for k, v in model.get('textures', {}).items():
                textures.setdefault(k, v)
            if model.get('display', {}).get('gui') is not None and gui is None:
                gui = model['display']['gui']
            if model.get('elements') and elements is None:
                elements = model['elements']
                elements_owner = model
                texture_size = model.get('texture_size')
            parent = model.get('parent')
            if not parent:
                break
            if parent in BUILTIN_FLAT_PARENTS:
                break
            cur = parent
            seen += 1
        chain = ModelChain(model_id, textures, elements, texture_size or [16, 16],
                           gui, missing)
        # texture_size belongs to the model that declares the elements
        if elements_owner is not None and elements_owner.get('texture_size'):
            chain.texture_size = elements_owner['texture_size']
        return chain

    # ----------------------------------------------------------- textures

    def mcmeta(self, png_name):
        """Parsed .png.mcmeta for an archive png path (or {})."""
        if png_name in self._mcmeta:
            return self._mcmeta[png_name]
        meta = {}
        cand = png_name + '.mcmeta'
        if cand in self.names:
            try:
                meta = json.loads(self.z.read(cand))
            except ValueError:
                meta = {}
        self._mcmeta[png_name] = meta
        return meta

    def frame_height(self, png_name, width, height):
        """Frame-0 height for an (optionally animated) texture strip."""
        meta = self.mcmeta(png_name).get('animation')
        if not meta:
            return height
        if meta.get('width'):
            return int(meta['width'])
        # default MC animation: square frames stacked vertically
        if height > width and height % width == 0:
            return width
        return height

    def load_texture(self, archive_path):
        """(w, h, rows, frame_h) for an archive png; rows cropped to frame 0."""
        if archive_path in self._textures:
            return self._textures[archive_path]
        raw = self.z.read(archive_path)
        try:
            w, h, rows = decode_png(raw)
        except PngError as exc:
            raise PngError('%s: %s' % (archive_path, exc))
        frame_h = self.frame_height(archive_path, w, h)
        if frame_h < h:
            rows = rows[:frame_h]
            h = frame_h
        self._textures[archive_path] = (w, h, rows)
        return self._textures[archive_path]

    def resolve_texture(self, ref, model_ns='minecraft'):
        """Texture id -> archive png path (model's namespace first, then vanilla)."""
        ns, _, path = ref.partition(':')
        if not path:
            ns, path = model_ns, ref
        candidates = ['assets/%s/textures/%s.png' % (ns, path),
                      'assets/minecraft/textures/%s.png' % path,
                      'assets/custom/textures/%s.png' % path,
                      'assets/mythicweapons/textures/%s.png' % path,
                      'assets/altarsmp/textures/%s.png' % path]
        for cand in candidates:
            if cand in self.names:
                return cand
        return None

    def used_texture_files(self, chain):
        """Archive png paths referenced by the chain's faces, face order first,
        deterministic (element order, then face name); falls back to layer0."""
        refs = []
        texmap = chain.textures

        def face_ref(face):
            t = face.get('texture', '')
            if isinstance(t, str) and t.startswith('#'):
                t = texmap.get(t[1:], '')
            return t if t not in ('', 'missing', 'missingno') else None

        for el in chain.elements or []:
            for fname in sorted(el.get('faces', {})):
                ref = face_ref(el['faces'][fname])
                if ref and ref not in refs:
                    refs.append(ref)
        if not refs:
            for k in sorted(texmap):
                t = texmap[k]
                if isinstance(t, str) and t not in ('', 'missing', 'missingno'):
                    refs.append(t)
                    break
        files = []
        for ref in refs:
            path = self.resolve_texture(ref, self.load_model(chain.model_id).get('_ns', 'minecraft')
                                        if self.load_model(chain.model_id) else 'minecraft')
            if path and path not in files:
                files.append(path)
        return files

    # ----------------------------------------------------------- dispatch

    def dispatch_entry(self, material, cmd):
        """The dispatch entry for (material, cmd): 'model' | 'no-dispatch' |
        'no-threshold' -> (model_id|None, how)."""
        name = 'assets/minecraft/items/%s.json' % material.lower()
        if name not in self.names:
            return None, 'no-dispatch'
        root = json.loads(self.z.read(name)).get('model', {})
        if norm_type(root.get('type')) != 'range_dispatch':
            return None, 'no-dispatch'
        hit = None
        for entry in root.get('entries', []):
            if entry.get('threshold') == cmd:
                hit = entry.get('model')
                break
        if hit is None:
            return None, 'no-threshold'
        return self._unwrap(hit), 'model'

    def _unwrap(self, node):
        """First concrete model id under condition/select wrappers."""
        if isinstance(node, str):
            return node
        t = norm_type(node.get('type'))
        if t == 'model':
            return node.get('model')
        if t == 'condition':
            return self._unwrap(node.get('on_false')) or self._unwrap(node.get('on_true'))
        if t == 'select':
            for case in node.get('cases', []):
                if 'model' in case:
                    found = self._unwrap(case['model'])
                    if found:
                        return found
                for sub in case.get('models', []):
                    found = self._unwrap(sub)
                    if found:
                        return found
            if 'fallback' in node:
                return self._unwrap(node['fallback'])
        return None

    def declared_thresholds(self, material):
        """Declared custom_model_data thresholds for a base material (or [])."""
        name = 'assets/minecraft/items/%s.json' % material.lower()
        if name not in self.names:
            return []
        root = json.loads(self.z.read(name)).get('model', {})
        if norm_type(root.get('type')) != 'range_dispatch':
            return []
        return sorted(e.get('threshold') for e in root.get('entries', []))


# ------------------------------------------------------------------ content

def load_content(content_dir=CONTENT_DIR):
    """The 60 custom_model_data-carrying catalog entries, in stable file order."""
    entries = []
    for fname in ('weapons_s1', 'weapons_s2', 'armor', 'items'):
        path = os.path.join(content_dir, '%s.json' % fname)
        with open(path) as fh:
            data = json.load(fh)
        for idx, entry in enumerate(data):
            if entry.get('custom_model_data') is None:
                continue
            e = dict(entry)
            e['_file'] = fname
            e['_idx'] = idx
            e['_name'] = e.get('id') or camel_to_snake(e['class'])
            entries.append(e)
    return entries


def resolve_entries(pack, entries):
    """Attach _model/_how/_res/_bedrock to every entry and return the
    deduplicated primary rows (one per Bedrock item)."""
    # pass 1: dispatch lookup for every entry
    for e in entries:
        mat = (e.get('base_material') or '').lower()
        if mat:
            model_id, how = pack.dispatch_entry(mat, e['custom_model_data'])
        else:
            model_id, how = None, 'no-dispatch'
        e['_model'], e['_how'] = model_id, how
    # pass 2: entries the pack cannot draw borrow the model of a sibling with
    # the identical display name (copper_diamond_* <-> copper_* armor)
    for e in entries:
        if e['_model'] is not None:
            continue
        sib = next((s for s in entries
                    if s is not e and s.get('display_name')
                    and s['display_name'] == e.get('display_name')
                    and s['_how'] == 'model'), None)
        if sib is not None:
            e['_model'], e['_how'] = sib['_model'], 'sibling'

    # group by visual: one Bedrock item per distinct (material, cmd) that draws
    groups = {}
    for e in entries:
        if e['_how'] in ('model', 'sibling'):
            groups.setdefault((e['base_material'].lower(), e['custom_model_data']), []).append(e)
    primaries = []
    for key in sorted(groups):
        grp = groups[key]
        primary = grp[0]
        primary['_is_primary'] = True
        primary['_alias_group'] = [g for g in grp if g is not primary]
        primaries.append(primary)
        for e in grp:
            e['_res'] = 'model'
            e['_bedrock'] = 'altarsmp:' + primary['_name']
            e['_model'] = primary['_model']
    for e in entries:
        if '_res' not in e:
            e['_res'] = 'vanilla'
            e['_bedrock'] = None
    return primaries
