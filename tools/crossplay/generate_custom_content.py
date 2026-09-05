#!/usr/bin/env python3
"""Generate the AltarSMP-Custom-Content Bedrock/Geyser crossplay package.

Usage:  python3 tools/crossplay/generate_custom_content.py

Reads (all committed, all read-only):
- fabric-mod/src/main/resources/altarsmp/content/*.json   (the 60 mapped entries)
- AltarSMP-ResourcePack.zip                                (authoritative assets)
- fabric-mod/src/main/resources/assets/altarsmp/icon.png   (pack icon)

Writes (deterministically - see docs/CROSSPLAY-HANDOFF.md):
- AltarSMP-Custom-Content/geyser/altarsmp-custom-items.json
- AltarSMP-Custom-Content/packs/pack.zip
- AltarSMP-Custom-Content/inventory.json
- AltarSMP-Custom-Content/README.md

Determinism contract: sorted archive entries, fixed timestamps (1980-02-01),
fixed compression level, stable JSON key order, no absolute paths, no
timestamps or hostnames anywhere. Two clean runs produce identical bytes.
"""

import json
import os
import re
import shutil
import uuid
import zipfile

import geometry
import render
from javapack import (CONTENT_DIR, JavaPack, MOD_ICON, REPO_ROOT,
                      load_content, resolve_entries)

OUT_DIR = os.path.join(REPO_ROOT, 'AltarSMP-Custom-Content')
VERSION = (2, 0, 5)
ZIP_TIMESTAMP = (1980, 2, 1, 0, 0, 0)

HEADER_UUID = str(uuid.uuid5(uuid.NAMESPACE_URL, 'https://altarsmp.fabric/crossplay/pack-header'))
MODULE_UUID = str(uuid.uuid5(uuid.NAMESPACE_URL, 'https://altarsmp.fabric/crossplay/pack-module'))

# ---------------------------------------------------------------- mini-format

_BEDROCK16 = [
    ('§0', (0, 0, 0)), ('§1', (0, 0, 170)), ('§2', (0, 170, 0)), ('§3', (0, 170, 170)),
    ('§4', (170, 0, 0)), ('§5', (170, 0, 170)), ('§6', (255, 170, 0)), ('§7', (170, 170, 170)),
    ('§8', (85, 85, 85)), ('§9', (85, 85, 255)), ('§a', (85, 255, 85)), ('§b', (85, 255, 255)),
    ('§c', (255, 85, 85)), ('§d', (255, 85, 255)), ('§e', (255, 255, 85)), ('§f', (255, 255, 255)),
]
_NAMED = {'black': '§0', 'dark_blue': '§1', 'dark_green': '§2', 'dark_aqua': '§3',
          'dark_red': '§4', 'dark_purple': '§5', 'gold': '§6', 'gray': '§7',
          'dark_gray': '§8', 'blue': '§9', 'green': '§a', 'aqua': '§b', 'red': '§c',
          'light_purple': '§d', 'yellow': '§e', 'white': '§f'}
_STYLES = {'bold': '§l', 'italic': '§o', 'underlined': '§n', 'strikethrough': '§m',
           'obfuscated': '§k'}


def _nearest_hex(hexc):
    r, g, b = int(hexc[1:3], 16), int(hexc[3:5], 16), int(hexc[5:7], 16)
    code, best = '§f', 1 << 30
    for c, (cr, cg, cb) in _BEDROCK16:
        d = (r - cr) ** 2 + (g - cg) ** 2 + (b - cb) ** 2
        if d < best:
            best, code = d, c
    return code


def mini_to_bedrock(text):
    """The plugin's MiniMessage-ish display strings -> Bedrock section codes.

    Named colours and styles map 1:1; gradients collapse to their first stop
    quantised to the nearest of the sixteen Bedrock colours (Bedrock has no
    per-character hex colours via legacy codes).
    """
    if not text:
        return ''
    out = []
    for tok in re.split(r'(<[^>]+>)', text):
        if not tok:
            continue
        m = re.fullmatch(r'<([a-z_]+)>', tok)
        if m:
            out.append(_NAMED.get(m.group(1)) or _STYLES.get(m.group(1), ''))
            continue
        m = re.fullmatch(r'<gradient:([#0-9a-fA-F:]+)>', tok)
        if m:
            first = m.group(1).split(':')[0]
            out.append(_nearest_hex(first) if first.startswith('#') else '')
            continue
        if re.fullmatch(r'</[a-z_]+>', tok):
            out.append('§r')
            continue
        out.append(tok)
    return ''.join(out)


def plain(text):
    return re.sub(r'§[0-9a-fk-or]', '', text)


# ---------------------------------------------------------------- pack model

def _creative_category(entry):
    if entry['_file'] in ('weapons_s1', 'weapons_s2', 'armor'):
        return 'equipment'
    return 'items'


def _handheld(entry):
    return entry['base_material'].lower() in (
        'netherite_sword', 'netherite_axe', 'netherite_pickaxe', 'bow',
        'crossbow', 'trident', 'mace', 'diamond_spear')


def _display_name(entry):
    raw = entry.get('display_name') or entry.get('plain_display')
    if raw:
        return mini_to_bedrock(raw)
    return entry['_name'].replace('_', ' ').title()


def build_model(pack):
    """Resolve every catalog entry; return (entries, primaries)."""
    entries = load_content(CONTENT_DIR)
    primaries = resolve_entries(pack, entries)
    return entries, primaries


def build_mapping(entries):
    """Geyser custom-item mappings (format_version 2, legacy definitions)."""
    mapping = {}
    for e in entries:
        if e['_res'] != 'model':
            continue
        java = 'minecraft:' + e['base_material'].lower()
        bucket = mapping.setdefault(java, [])
        if any(d.get('custom_model_data') == e['custom_model_data'] for d in bucket):
            continue  # aliased entries share the (java id, cmd) pair
        stackable = e['_file'] == 'items' and not e.get('unbreakable')
        bucket.append({
            'type': 'legacy',
            'custom_model_data': e['custom_model_data'],
            'bedrock_identifier': e['_bedrock'],
            'display_name': plain(_display_name(e)),
            'bedrock_options': {
                'icon': 'altarsmp_' + e['_bedrock'].split(':', 1)[1],
                'display_handheld': _handheld(e),
                'creative_category': _creative_category(e),
            },
            'components': {'minecraft:max_stack_size': 64 if stackable else 1},
        })
    for java in mapping:
        mapping[java].sort(key=lambda d: d['custom_model_data'])
    return mapping


def build_inventory(pack, entries, primaries):
    rows = []
    primary_by_id = {}
    for p in primaries:
        primary_by_id[(p['base_material'].lower(), p['custom_model_data'])] = p
    for e in entries:
        key = (e['base_material'].lower(), e['custom_model_data'])
        alias_of = None
        if e['_res'] == 'model' and not e.get('_is_primary') and primary_by_id.get(key) is not e:
            p = primary_by_id[key]
            alias_of = '%s.json#%s' % (p['_file'], p.get('id') or p['class'])
        row = {
            'file': e['_file'],
            'java': {'id': e.get('id'), 'class': e.get('class'),
                     'base_material': e['base_material'],
                     'custom_model_data': e['custom_model_data']},
            'resolution': e['_res'],
            'bedrock_identifier': e['_bedrock'],
            'alias_of': alias_of,
            'tooltip_style': e.get('tooltip_style'),
        }
        if e['_res'] == 'model':
            chain = pack.chain(e['_model'])
            row['model'] = e['_model']
            row['model_kind'] = '3d' if chain.is_3d else 'flat'
            row['texture_size'] = chain.texture_size
            row['icon'] = 'textures/items/%s.png' % e['_bedrock'].split(':', 1)[1]
        rows.append(row)
    return {
        'package': 'AltarSMP-Custom-Content',
        'version': '.'.join(str(v) for v in VERSION),
        'entries': rows,
        'alias_note': ('entries sharing one (base_material, custom_model_data) pair render as one '
                       'Bedrock item; alias_of names the primary catalog entry'),
    }


def build_pack_files(pack, primaries, mapping):
    """Every file that goes inside packs/pack.zip."""
    files = {}
    files['manifest.json'] = {
        'format_version': 2,
        'header': {
            'name': 'AltarSMP Custom Content',
            'description': 'AltarSMP 2.0.5 custom items for Bedrock players (Geyser crossplay)',
            'uuid': HEADER_UUID,
            'version': list(VERSION),
            'min_engine_version': [1, 21, 0],
        },
        'modules': [{'type': 'resources', 'uuid': MODULE_UUID, 'version': list(VERSION),
                     'name': 'altarsmp custom content'}],
    }
    texture_data = {}
    lang_lines = []
    for p in primaries:
        name = p['_name']
        ident = p['_bedrock']
        chain = pack.chain(p['_model'])
        components = {
            'minecraft:icon': {'textures': {'default': 'altarsmp_' + name}},
            'minecraft:display_name': {'value': _display_name(p)},
            'minecraft:max_stack_size': 64 if (p['_file'] == 'items' and not p.get('unbreakable')) else 1,
        }
        files['items/%s.json' % name] = {
            'format_version': '1.21.0',
            'minecraft:item': {
                'description': {'identifier': ident,
                                'menu_category': {'category': _creative_category(p)}},
                'components': components,
            },
        }
        lang_lines.append('item.%s=%s' % (ident, plain(_display_name(p))))
        texture_data['altarsmp_' + name] = {'textures': ['textures/items/%s' % name]}
        # icon: rendered gui view for 3D models, scaled texture for flat ones
        if chain.is_3d:
            icon = render.render_model(pack, chain)
        else:
            used = pack.used_texture_files(chain)
            icon = render.render_texture(pack, used[0])
        files['textures/items/%s.png' % name] = icon
        if chain.is_3d:
            geo, atlas = geometry.build_geometry(pack, chain, name)
            files['models/entity/%s.geo.json' % name] = geo
            files['textures/attachables/%s.png' % name] = atlas
            files['attachables/%s.json' % name] = {
                'format_version': '1.10.0',
                'minecraft:attachable': {
                    'description': {
                        'identifier': ident,
                        'materials': {'default': 'entity_alphatest',
                                      'enchanted': 'entity_alphatest_glint'},
                        'textures': {'default': 'textures/attachables/%s' % name,
                                     'enchanted': 'textures/misc/enchanted_item_glint'},
                        'geometry': {'default': 'geometry.altarsmp.%s' % name},
                        'render_controllers': ['controller.render.default'],
                    },
                },
            }
    files['textures/item_texture.json'] = {
        'resource_pack_name': 'altarsmp.custom_content',
        'texture_name': 'atlas.items',
        'texture_data': texture_data,
    }
    files['texts/en_US.lang'] = '\n'.join(sorted(lang_lines)) + '\n'
    # the ability artwork, restored from the Java pack (png payloads only; the
    # Java-side .mcmeta animation metadata does not apply to Bedrock)
    for n in sorted(pack.names):
        if n.startswith('assets/altarsmp/textures/gui/sprites/tooltip/') and n.endswith('.png'):
            files[n[len('assets/altarsmp/'):]] = pack.z.read(n)
    with open(MOD_ICON, 'rb') as fh:
        files['pack_icon.png'] = fh.read()
    return files


def jdump(obj):
    return json.dumps(obj, indent=1, ensure_ascii=False, sort_keys=True) + '\n'


def zip_bytes(files):
    """Deterministic zip: sorted entries, fixed timestamp, fixed compression."""
    import io
    buf = io.BytesIO()
    zf = zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=9)
    for path in sorted(files):
        info = zipfile.ZipInfo(path, date_time=ZIP_TIMESTAMP)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        info.create_system = 0
        payload = files[path]
        if not isinstance(payload, bytes):
            payload = jdump(payload).encode('utf-8')
        zf.writestr(info, payload)
    zf.close()
    return buf.getvalue()


README = """# AltarSMP-Custom-Content (2.0.5)

Geyser/Bedrock crossplay package for the AltarSMP Fabric port, rebuilt from
scratch by `tools/crossplay/`.

- `geyser/altarsmp-custom-items.json` - Geyser custom-item mappings
  (format_version 2, `legacy` definitions keyed on `custom_model_data`, one
  entry per Java (item, model data) pair). Copy into Geyser's `custom_mappings/`
  folder.
- `packs/pack.zip` - the Bedrock resource pack: `manifest.json`, one Bedrock
  item definition per custom item, `item_texture.json`, rendered 128x128 icons,
  attachables + geometry + packed textures for the 3D weapon models, and the
  ability tooltip artwork restored from the Java pack. Copy into Geyser's
  `resource_packs/` folder (or a Bedrock server's resource packs).
- `inventory.json` - all sixty Java entries cross-referenced to their Bedrock
  item (or recorded as deliberately vanilla-looking).

Regenerate with `python3 tools/crossplay/generate_custom_content.py`, verify
with `python3 tools/crossplay/validate_custom_content.py`. See
docs/CROSSPLAY-HANDOFF.md for the full story.
"""


def main():
    pack = JavaPack()
    entries, primaries = build_model(pack)
    mapping = build_mapping(entries)
    inventory = build_inventory(pack, entries, primaries)
    pack_files = build_pack_files(pack, primaries, mapping)
    blob = zip_bytes(pack_files)

    if os.path.exists(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(os.path.join(OUT_DIR, 'geyser'))
    os.makedirs(os.path.join(OUT_DIR, 'packs'))
    with open(os.path.join(OUT_DIR, 'geyser', 'altarsmp-custom-items.json'), 'w') as fh:
        fh.write(jdump({'format_version': 2, 'items': mapping}))
    with open(os.path.join(OUT_DIR, 'inventory.json'), 'w') as fh:
        fh.write(jdump(inventory))
    with open(os.path.join(OUT_DIR, 'packs', 'pack.zip'), 'wb') as fh:
        fh.write(blob)
    with open(os.path.join(OUT_DIR, 'README.md'), 'w') as fh:
        fh.write(README)

    import hashlib
    print('entries: %d (model %d, vanilla %d)' % (
        len(entries),
        sum(1 for e in entries if e['_res'] == 'model'),
        sum(1 for e in entries if e['_res'] == 'vanilla')))
    print('bedrock items: %d, mapping pairs: %d' % (
        len(primaries), sum(len(v) for v in mapping.values())))
    print('pack.zip: %d bytes sha256 %s' % (len(blob), hashlib.sha256(blob).hexdigest()))


if __name__ == '__main__':
    main()
