#!/usr/bin/env python3
"""Validate the AltarSMP-Custom-Content package (29 checks).

Run from the repository root:  python3 tools/crossplay/validate_custom_content.py

The checks mirror docs/CROSSPLAY-HANDOFF.md and cover: manifest validity, zip
hygiene (no traversal, deterministic layout), Geyser mapping completeness,
Bedrock item definitions and icons, geometry/attachable references, UV bases,
the ability artwork, catalogue collisions and byte-for-byte reproducibility.
"""

import hashlib
import io
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import geometry
import render
from javapack import (CONTENT_DIR, JavaPack, MOD_ICON, REPO_ROOT,
                      load_content, resolve_entries)

OUT_DIR = os.path.join(REPO_ROOT, 'AltarSMP-Custom-Content')
MAPPING = os.path.join(OUT_DIR, 'geyser', 'altarsmp-custom-items.json')
INVENTORY = os.path.join(OUT_DIR, 'inventory.json')
PACK = os.path.join(OUT_DIR, 'packs', 'pack.zip')
ICON_SIZE = render.ICON_SIZE

CHECKS = []


def check(fn):
    CHECKS.append(fn)
    return fn


def load_json(path):
    with open(path) as fh:
        return json.load(fh)


def snake_ok(name):
    return bool(re.fullmatch(r'[a-z][a-z0-9_]*', name))


# ---------------------------------------------------------------- environment

@check
def c01_inputs_exist(results):
    """Authoritative inputs are present: the pack zip, the content directory
    and the mod icon the generator reads."""
    missing = [p for p in (REPO_ROOT and os.path.join(REPO_ROOT, 'AltarSMP-ResourcePack.zip'),
                           CONTENT_DIR, MOD_ICON) if not os.path.exists(p)]
    results.append('missing: %s' % missing if missing else
                   'AltarSMP-ResourcePack.zip, content/, icon.png all present')
    return not missing


@check
def c02_mod_version(results):
    """fabric-mod/gradle.properties declares mod_version=2.0.5."""
    props = {}
    with open(os.path.join(REPO_ROOT, 'fabric-mod', 'gradle.properties')) as fh:
        for line in fh:
            if '=' in line and not line.strip().startswith('#'):
                k, v = line.split('=', 1)
                props[k.strip()] = v.strip()
    got = props.get('mod_version')
    results.append('mod_version=%s' % got)
    return got == '2.0.5'


# ---------------------------------------------------------------- catalogue

@check
def c03_content_counts(results):
    """Catalogue counts: armor 8, items 22, weapons_s1 24, weapons_s2 6 entries
    carrying custom_model_data (60 mapped entries in total)."""
    want = {'armor': 8, 'items': 22, 'weapons_s1': 24, 'weapons_s2': 6}
    got = {}
    for fname in want:
        data = load_json(os.path.join(CONTENT_DIR, '%s.json' % fname))
        got[fname] = sum(1 for e in data if e.get('custom_model_data') is not None)
    results.append('%s (want %s)' % (got, want))
    return got == want


@check
def c04_cmd_values(results):
    """Every custom_model_data is a non-negative integer."""
    bad = []
    for e in load_content():
        cmd = e['custom_model_data']
        if not isinstance(cmd, int) or isinstance(cmd, bool) or cmd < 0:
            bad.append((e['_name'], cmd))
    results.append('all non-negative ints' if not bad else 'bad: %s' % bad)
    return not bad


@check
def c05_no_geyser_pair_collisions(results):
    """No two Java entries collide on (base material, custom_model_data) in the
    Geyser mapping: each (minecraft id, cmd) pair appears at most once, so two
    Bedrock items can never claim the same Java look."""
    mapping = load_json(MAPPING)
    seen = {}
    dup = []
    for java, defs in mapping['items'].items():
        for d in defs:
            pair = (java, d['custom_model_data'])
            if pair in seen:
                dup.append(pair)
            seen[pair] = d['bedrock_identifier']
    results.append('%d unique (java, cmd) pairs' % len(seen) if not dup
                   else 'collisions: %s' % dup)
    return not dup


@check
def c06_vanilla_entries_deliberate(results):
    """Entries recorded as vanilla-looking really have no dispatcher threshold
    for their custom_model_data (the reserved discriminators)."""
    pack = JavaPack()
    bad = []
    for e in load_content():
        mat = e['base_material'].lower()
        thresholds = pack.declared_thresholds(mat)
        if e['custom_model_data'] in thresholds:
            continue  # dispatchable -> must be mapped, checked elsewhere
    inventory = load_json(INVENTORY)
    van = [r for r in inventory['entries'] if r['resolution'] == 'vanilla']
    for row in van:
        thresholds = pack.declared_thresholds(row['java']['base_material'].lower())
        if row['java']['custom_model_data'] in thresholds:
            bad.append(row['java'])
    results.append('%d vanilla-look entries, all on undeclared thresholds' % len(van)
                   if not bad else 'wrongly vanilla: %s' % bad)
    return not bad and len(van) == 10


@check
def c07_cmds_within_declared_ranges(results):
    """All mapped custom_model_data values fall inside the dispatcher's declared
    thresholds for their base material (exact-threshold dispatch)."""
    pack = JavaPack()
    entries = load_content()
    inventory = load_json(INVENTORY)
    mapped = {(r['java']['id'], r['java']['class'])
              for r in inventory['entries'] if r['resolution'] == 'model'}
    bad = []
    n = 0
    for e in entries:
        if (e.get('id'), e.get('class')) not in mapped:
            continue  # deliberate vanilla-look entries ride undeclared slots
        mat = e['base_material'].lower()
        thresholds = pack.declared_thresholds(mat)
        if e['custom_model_data'] in thresholds:
            n += 1
        else:
            # a sibling-borrowed entry rides its sibling's dispatcher slot
            sib = [s for s in entries
                   if s.get('display_name') == e.get('display_name')
                   and s['custom_model_data'] in pack.declared_thresholds(s['base_material'].lower())]
            if not sib:
                bad.append((e['_name'], e['custom_model_data'], thresholds))
    results.append('%d mapped entries on declared dispatcher thresholds' % n if not bad
                   else 'undeclared: %s' % bad)
    return not bad


@check
def c08_inventory_shape(results):
    """inventory.json covers all 60 entries: 50 model-mapped, 10 deliberate
    vanilla-look, 46 distinct Bedrock items."""
    inventory = load_json(INVENTORY)
    rows = inventory['entries']
    model = sum(1 for r in rows if r['resolution'] == 'model')
    vanilla = sum(1 for r in rows if r['resolution'] == 'vanilla')
    items = len({r['bedrock_identifier'] for r in rows if r['bedrock_identifier']})
    ok = len(rows) == 60 and model == 50 and vanilla == 10 and items == 46
    results.append('rows=%d model=%d vanilla=%d bedrock_items=%d' %
                   (len(rows), model, vanilla, items))
    return ok


# ---------------------------------------------------------------- geyser map

@check
def c09_mapping_format(results):
    """Geyser mapping declares format_version 2."""
    mapping = load_json(MAPPING)
    results.append('format_version=%s' % mapping.get('format_version'))
    return mapping.get('format_version') == 2


@check
def c10_java_ids_namespaced(results):
    """Every mapping key is a namespaced Java item id (minecraft:...)."""
    mapping = load_json(MAPPING)
    bad = [k for k in mapping['items'] if not re.fullmatch(r'minecraft:[a-z0-9_]+', k)]
    results.append('%d java ids, all minecraft:-namespaced' % len(mapping['items'])
                   if not bad else 'bad keys: %s' % bad)
    return not bad and len(mapping['items']) > 0


@check
def c11_legacy_definitions(results):
    """Every definition is a legacy one with an integer custom_model_data."""
    mapping = load_json(MAPPING)
    bad = []
    n = 0
    for defs in mapping['items'].values():
        for d in defs:
            n += 1
            if d.get('type') != 'legacy' or not isinstance(d.get('custom_model_data'), int) \
                    or d['custom_model_data'] < 0:
                bad.append(d.get('bedrock_identifier'))
    results.append('%d legacy definitions' % n if not bad else 'bad: %s' % bad)
    return not bad and n >= 46


@check
def c12_bedrock_identifiers_unique(results):
    """Bedrock identifiers are unique across the whole mapping."""
    mapping = load_json(MAPPING)
    seen, dup = set(), []
    for defs in mapping['items'].values():
        for d in defs:
            bid = d['bedrock_identifier']
            if bid in seen:
                dup.append(bid)
            seen.add(bid)
    results.append('%d unique identifiers' % len(seen) if not dup else 'duplicates: %s' % dup)
    return not dup


@check
def c13_identifiers_snake_case(results):
    """Identifiers are altarsmp:-namespaced lowercase snake_case."""
    mapping = load_json(MAPPING)
    bad = []
    for defs in mapping['items'].values():
        for d in defs:
            bid = d['bedrock_identifier']
            ns, _, name = bid.partition(':')
            if ns != 'altarsmp' or not snake_ok(name):
                bad.append(bid)
    results.append('all altarsmp: lowercase_snake_case' if not bad else 'bad: %s' % bad)
    return not bad


@check
def c14_mappings_resolve_to_definitions(results):
    """Every Geyser bedrock_identifier resolves to a Bedrock item definition in
    the pack (items/<name>.json)."""
    zf = zipfile.ZipFile(PACK)
    names = set(zf.namelist())
    missing = []
    n = 0
    mapping = load_json(MAPPING)
    for defs in mapping['items'].values():
        for d in defs:
            n += 1
            name = d['bedrock_identifier'].split(':', 1)[1]
            if 'items/%s.json' % name not in names:
                missing.append(d['bedrock_identifier'])
    results.append('%d mappings, all resolve to item definitions' % n if not missing
                   else 'dangling: %s' % missing)
    return not missing


@check
def c15_no_invented_pairs(results):
    """Every (java id, cmd) in the mapping comes from the content catalogue -
    the mapping invents nothing."""
    content_pairs = {(e['base_material'].lower(), e['custom_model_data'])
                     for e in load_content()}
    extra = []
    mapping = load_json(MAPPING)
    for java, defs in mapping['items'].items():
        mat = java.split(':', 1)[1]
        for d in defs:
            if (mat, d['custom_model_data']) not in content_pairs:
                extra.append((java, d['custom_model_data']))
    results.append('every mapping pair traces to the catalogue' if not extra
                   else 'invented: %s' % extra)
    return not extra


# ---------------------------------------------------------------- manifest

def _uuid_valid(s):
    return bool(re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', s))


@check
def c16_manifest_uuids(results):
    """manifest.json: format_version 2 and valid, distinct v4 header/module
    UUIDs."""
    zf = zipfile.ZipFile(PACK)
    manifest = json.loads(zf.read('manifest.json'))
    header, modules = manifest.get('header', {}), manifest.get('modules', [])
    ok = manifest.get('format_version') == 2 and _uuid_valid(header.get('uuid', ''))
    ids = [header.get('uuid')] + [m.get('uuid') for m in modules]
    ok = ok and len(ids) == len(set(ids)) and all(_uuid_valid(i) for i in ids if i)
    results.append('format_version=2, uuids valid and distinct')
    return ok


@check
def c17_manifest_versions(results):
    """manifest.json: version [2,0,5] and min_engine_version triads."""
    zf = zipfile.ZipFile(PACK)
    manifest = json.loads(zf.read('manifest.json'))
    header, modules = manifest['header'], manifest['modules']
    ok = header.get('version') == [2, 0, 5]
    ok = ok and all(isinstance(v, int) for v in header.get('min_engine_version', []))
    ok = ok and all(m.get('version') == [2, 0, 5] for m in modules)
    results.append('version=%s min_engine=%s modules=%d' %
                   (header['version'], header['min_engine_version'], len(modules)))
    return ok


# ---------------------------------------------------------------- pack zip

@check
def c18_no_path_traversal(results):
    """Pack zip entries are safe: no '..', no absolute paths, no backslashes,
    no drive letters - zips cleanly with no path traversal."""
    zf = zipfile.ZipFile(PACK)
    bad = []
    for n in zf.namelist():
        parts = n.split('/')
        if (n.startswith('/') or '\\' in n or ':' in n or '..' in parts
                or '' in parts[:-1] or not n):
            bad.append(n)
    results.append('%d entries, all safe' % len(zf.namelist()) if not bad
                   else 'unsafe: %s' % bad)
    return not bad


@check
def c19_zip_deterministic_layout(results):
    """Pack zip entry names are unique and sorted; every timestamp is the fixed
    1980-02-01 determinism stamp."""
    zf = zipfile.ZipFile(PACK)
    names = zf.namelist()
    dup = len(names) != len(set(names))
    sorted_ok = names == sorted(names)
    stamps = {i.date_time for i in zf.infolist()}
    results.append('%d entries, unique+sorted, timestamps=%s' % (len(names), sorted(stamps)))
    return not dup and sorted_ok and stamps == {(1980, 2, 1, 0, 0, 0)}


@check
def c20_item_definitions_shape(results):
    """Every item definition: format_version 1.21.0, identifier matches the
    file name, menu_category present."""
    zf = zipfile.ZipFile(PACK)
    bad = []
    n = 0
    for n_ in zf.namelist():
        if not n_.startswith('items/') or not n_.endswith('.json'):
            continue
        n += 1
        spec = json.loads(zf.read(n_))
        inner = spec.get('minecraft:item', {})
        desc = inner.get('description', {})
        want = 'altarsmp:' + n_[len('items/'):-len('.json')]
        if (spec.get('format_version') != '1.21.0'
                or desc.get('identifier') != want
                or 'category' not in desc.get('menu_category', {})):
            bad.append(n_)
    results.append('%d definitions, all well-formed' % n if not bad else 'bad: %s' % bad)
    return not bad and n == 46


@check
def c21_definition_count_matches(results):
    """The pack ships exactly one definition per distinct Bedrock item (46)."""
    zf = zipfile.ZipFile(PACK)
    defs = [n_ for n_ in zf.namelist() if n_.startswith('items/') and n_.endswith('.json')]
    inventory = load_json(INVENTORY)
    want = {r['bedrock_identifier'] for r in inventory['entries'] if r['bedrock_identifier']}
    got = {'altarsmp:' + d[len('items/'):-len('.json')] for d in defs}
    results.append('%d definitions == %d inventory identifiers' % (len(defs), len(want)))
    return len(defs) == 46 and got == want


# ---------------------------------------------------------------- icons

@check
def c22_icons_exist(results):
    """Every definition's minecraft:icon resolves to a packed texture that
    exists in textures/items/ - no dangling references in either direction -
    and every icon file is the expected square power-of-two size (128x128)."""
    import pngio
    zf = zipfile.ZipFile(PACK)
    names = set(zf.namelist())
    texture_data = json.loads(zf.read('textures/item_texture.json'))['texture_data']
    missing = []
    n = 0
    for n_ in sorted(zf.namelist()):
        if not n_.startswith('items/') or not n_.endswith('.json'):
            continue
        n += 1
        spec = json.loads(zf.read(n_))
        icon = spec['minecraft:item']['components']['minecraft:icon']['textures']['default']
        entry = texture_data.get(icon)
        path = entry['textures'][0] + '.png' if entry else None
        if not path or path not in names:
            missing.append((n_, icon, 'dangling'))
    for n_ in sorted(names):
        if n_.startswith('textures/items/') and n_.endswith('.png'):
            w, h = pngio.png_size(zf.read(n_))
            if w != h or w & (w - 1) or w != ICON_SIZE:
                missing.append((n_, w, h, 'not %dx%d square POT' % (ICON_SIZE, ICON_SIZE)))
    results.append('%d icons referenced, all present and %dx%d square POT'
                   % (n, ICON_SIZE, ICON_SIZE) if not missing
                   else 'bad: %s' % missing)
    return not missing


@check
def c24_texture_atlas_resolves(results):
    """item_texture.json entries and textures/items/*.png match exactly in both
    directions - all texture references resolve."""
    zf = zipfile.ZipFile(PACK)
    texture_data = json.loads(zf.read('textures/item_texture.json'))['texture_data']
    pngs = {n_[len('textures/items/'):-len('.png')]
            for n_ in zf.namelist() if n_.startswith('textures/items/') and n_.endswith('.png')}
    mapped = set()
    dangling = []
    for key, entry in texture_data.items():
        for t in entry['textures']:
            name = t.split('/')[-1]
            mapped.add(name)
            if name not in pngs:
                dangling.append(t)
    extra = pngs - mapped
    results.append('%d atlas entries <-> %d icon files' % (len(texture_data), len(pngs)))
    return not dangling and not extra


@check
def c25_icons_fit_canvas(results):
    """Auto-fit / no clipping: every rendered 3D-model icon's opaque pixels
    stay inside the canvas with at least a one-pixel border (flat icons are
    exempt - a full-bleed sprite is the vanilla look they are meant to keep)."""
    import pngio
    zf = zipfile.ZipFile(PACK)
    inventory = load_json(INVENTORY)
    flat_names = {r['icon'][len('textures/items/'):-len('.png')]
                  for r in inventory['entries'] if r.get('model_kind') == 'flat'}
    bad = []
    n = 0
    for n_ in sorted(zf.namelist()):
        if not n_.startswith('textures/items/') or not n_.endswith('.png'):
            continue
        name = n_[len('textures/items/'):-len('.png')]
        w, h, rows = pngio.decode_png(zf.read(n_))
        pts = [(x, y) for y in range(h) for x in range(w) if rows[y][x][3]]
        if not pts:
            bad.append((name, 'empty'))
            continue
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        n += 1
        if name in flat_names:
            continue
        if min(xs) < 1 or min(ys) < 1 or max(xs) > w - 2 or max(ys) > h - 2:
            bad.append((name, (min(xs), min(ys), max(xs), max(ys))))
    results.append('%d rendered icons, all inside the 1px border' % n if not bad
                   else 'clipped/empty: %s' % bad)
    return not bad


# ---------------------------------------------------------------- geometry

@check
def c26_attachables_resolve(results):
    """Every attachable's identifier, geometry and texture references resolve
    inside the pack."""
    zf = zipfile.ZipFile(PACK)
    names = set(zf.namelist())
    bad = []
    n = 0
    for n_ in sorted(names):
        if not n_.startswith('attachables/') or not n_.endswith('.json'):
            continue
        n += 1
        spec = json.loads(zf.read(n_))['minecraft:attachable']['description']
        ident = spec['identifier']
        geo = spec['geometry']['default']
        tex = spec['textures']['default']
        want_geo = geo.split('.', 2)[-1] if geo.startswith('geometry.') else None
        ok = ident == 'altarsmp:' + n_[len('attachables/'):-len('.json')]
        ok = ok and ('models/entity/%s.geo.json' % want_geo) in names
        ok = ok and (tex + '.png') in names
        if not ok:
            bad.append(n_)
    results.append('%d attachables, all references resolve' % n if not bad
                   else 'dangling: %s' % bad)
    return not bad and n == 34


@check
def c27_geometry_uv_inside_atlas(results):
    """Every geometry face UV rect fits inside its atlas (uv and uv+uv_size
    within texture_width/texture_height, which match the packed png)."""
    import pngio
    zf = zipfile.ZipFile(PACK)
    bad = []
    n = 0
    for n_ in sorted(zf.namelist()):
        if not n_.startswith('models/entity/') or not n_.endswith('.geo.json'):
            continue
        n += 1
        geo = json.loads(zf.read(n_))['minecraft:geometry'][0]
        desc = geo['description']
        png_w, png_h = pngio.png_size(zf.read('textures/attachables/%s.png'
                                              % n_[len('models/entity/'):-len('.geo.json')]))
        if desc['texture_width'] != png_w or desc['texture_height'] != png_h:
            bad.append((n_, 'atlas size mismatch'))
            continue
        for bone in geo['bones']:
            for cube in bone.get('cubes', []):
                for face, rect in cube.get('uv', {}).items():
                    u, v = rect['uv']
                    us, vs = rect['uv_size']
                    if u < 0 or v < 0 or u + us > desc['texture_width'] + 1e-6 \
                            or v + vs > desc['texture_height'] + 1e-6:
                        bad.append((n_, face, u, v, us, vs))
    results.append('%d geometries, all UV rects inside the atlas' % n if not bad
                   else 'outside: %s' % bad)
    return not bad


@check
def c28_uv_honours_texture_size(results):
    """Face UV rects are sampled against each model's honoured Blockbench
    texture_size basis: exports laid out on the declared texture-size grid use
    it, vanilla-grid exports use the vanilla 16 scheme (texture_size is
    Blockbench-only metadata there). Recomputing every rect as
    min(raw)/basis * sub-texture size + the atlas offset must reproduce the
    shipped geometry exactly, with image-space v and direction-agnostic
    (min-based) rect sizes."""
    pack = JavaPack()
    entries = load_content()
    primaries = resolve_entries(pack, entries)
    zf = zipfile.ZipFile(PACK)
    bad = []
    n = 0
    for p in primaries:
        chain = pack.chain(p['_model'])
        if not chain.is_3d:
            continue
        n += 1
        name = p['_name']
        geo = json.loads(zf.read('models/entity/%s.geo.json' % name))
        desc = geo['minecraft:geometry'][0]['description']
        atlas_w, atlas_h = desc['texture_width'], desc['texture_height']
        shipped = {c: cu for c in []}  # noqa - built below per cube index
        cubes = geo['minecraft:geometry'][0]['bones'][0]['cubes']
        files = pack.used_texture_files(chain)
        # atlas placements, recomputed independently of geometry.py
        placements = []
        y = 0
        for path in files:
            w, h, _rows = pack.load_texture(path)
            placements.append((path, 0, y, w, h))
            y += h
        place = {pl[0]: pl for pl in placements}
        # the honoured basis, recomputed from the raw data (javapack.uv_basis)
        basis = [16, 16]
        if chain.texture_size and tuple(chain.texture_size) != (16, 16):
            if any(max(f['uv'][0], f['uv'][1], f['uv'][2], f['uv'][3]) > 16.0 + 1e-9
                   for el in chain.elements
                   for f in el.get('faces', {}).values() if f.get('uv')):
                basis = list(chain.texture_size)
        elements = [el for el in chain.elements if el.get('from') and el.get('to')]
        if len(cubes) != len(elements):
            bad.append((name, 'cube count %d vs %d' % (len(cubes), len(elements))))
            continue
        for cube, el in zip(cubes, elements):
            for face, rect in cube.get('uv', {}).items():
                raw = el['faces'][face]['uv']
                t = el['faces'][face].get('texture', '')
                if isinstance(t, str) and t.startswith('#'):
                    t = chain.textures.get(t[1:], '')
                path = pack.resolve_texture(t) if t and t not in ('missing', 'missingno') else None
                if path not in place:
                    path = files[0]
                _f, ax, ay, sub_w, sub_h = place[path]
                want_u = ax + min(raw[0], raw[2]) / float(basis[0]) * sub_w
                want_v = ay + min(raw[1], raw[3]) / float(basis[1]) * sub_h
                want_us = abs(raw[2] - raw[0]) / float(basis[0]) * sub_w
                want_vs = abs(raw[3] - raw[1]) / float(basis[1]) * sub_h
                got = rect['uv'] + rect['uv_size']
                want = [round(want_u, 4), round(want_v, 4), round(want_us, 4), round(want_vs, 4)]
                if any(abs(g - w_) > 0.01 for g, w_ in zip(got, want)):
                    bad.append((name, face, got, want))
        if bad and bad[-1][0] == name:
            continue
    results.append('%d 3D models sample UVs on their honoured basis' % n
                   if not bad else 'wrong basis: %s' % bad[:4])
    return not bad


# ---------------------------------------------------------------- artwork

@check
def c29_ability_artwork(results):
    """Ability artwork: every tooltip_style in the catalogue resolves to a
    background+frame pair in the pack, and all 96 artwork pngs are shipped."""
    zf = zipfile.ZipFile(PACK)
    names = set(zf.namelist())
    missing = []
    styles = set()
    for fname in ('weapons_s1', 'weapons_s2', 'armor'):
        for e in load_json(os.path.join(CONTENT_DIR, '%s.json' % fname)):
            if e.get('tooltip_style'):
                styles.add(e['tooltip_style'])
    for style in sorted(styles):
        for suffix in ('_background.png', '_frame.png'):
            path = 'textures/gui/sprites/tooltip/%s%s' % (style, suffix)
            if path not in names:
                missing.append(path)
    art = [n_ for n_ in names if n_.startswith('textures/gui/sprites/tooltip/')
           and n_.endswith('.png')]
    results.append('%d tooltip styles, %d artwork pngs' % (len(styles), len(art)))
    return not missing and len(art) == 96


# ---------------------------------------------------------------- reproducibility

@check
def c30_reproducible_bytes(results):
    """pack.zip is byte-for-byte reproducible: regenerating the package in this
    process reproduces the committed archive's sha256."""
    import generate_custom_content as gen
    pack = JavaPack()
    entries, primaries = gen.build_model(pack)
    mapping = gen.build_mapping(entries)
    files = gen.build_pack_files(pack, primaries, mapping)
    blob = gen.zip_bytes(files)
    with open(PACK, 'rb') as fh:
        shipped = fh.read()
    a, b = hashlib.sha256(blob).hexdigest(), hashlib.sha256(shipped).hexdigest()
    results.append('regenerated %s == shipped %s' % (a[:16], b[:16]))
    return a == b and len(blob) == len(shipped)


def main():
    failures = []
    for fn in CHECKS:
        results = []
        try:
            ok = fn(results)
        except Exception as exc:  # a broken check is a failed check
            ok = False
            results.append('exception: %r' % exc)
        status = 'ok  ' if ok else 'FAIL'
        print('%s %02d %s' % (status, CHECKS.index(fn) + 1, fn.__doc__.strip().splitlines()[0]))
        for r in results:
            print('       %s' % r)
        if not ok:
            failures.append(fn.__name__)
    total = len(CHECKS)
    print('validation: %d checks, %d failed' % (total, len(failures)))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
