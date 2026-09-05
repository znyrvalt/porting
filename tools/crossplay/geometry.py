"""Java element models -> Bedrock geometry + a packed attachable texture.

The Bedrock side renders 3D custom items through attachables, which need a
``minecraft:geometry`` and a single texture. Java item models may reference
several textures and animated strips, so each model gets a deterministic atlas:
its referenced textures (frame 0 of any animation) packed in reference order,
and every face's UV remapped into atlas texel coordinates.

Coordinate mapping (Java model space is 0..16, x east / y up / z south):

- cube size  = ``to - from``
- cube origin = ``[8 - to_x, from_y, from_z - 8]``  (x mirrored, y kept,
  z recentred) - the standard Java->Bedrock handedness fix-up
- single-axis element rotations carry over as cube rotation/pivot, with the
  angle sign flipped on the mirrored (x) axis

Like render.py this favours determinism and explicit, documented conventions
over client-exact rendering.
"""

import math

from pngio import encode_png

GEO_FORMAT = '1.12.0'


def _face_uv_rect(face, texsize):
    uv = face.get('uv', [0, 0, 16, 16])
    k = (face.get('rotation', 0) // 90) % 4
    u1, v1, u2, v2 = uv
    if k:
        # rotate the rect corner assignment by k*90 degrees about the rect centre
        cx, cy = (u1 + u2) / 2.0, (v1 + v2) / 2.0

        def rotp(u, v):
            dd_u, dd_v = u - cx, v - cy
            for _ in range(k):
                dd_u, dd_v = -dd_v, dd_u
            return cx + dd_u, cy + dd_v

        u1, v1 = rotp(u1, v1)
        u2, v2 = rotp(u2, v2)
    tx, ty = float(texsize[0]), float(texsize[1])
    return (u1 / tx, v1 / ty, u2 / tx, v2 / ty)


def build_atlas(pack, files):
    """Pack the referenced textures (frame 0) into one RGBA atlas."""
    images = [pack.load_texture(path) for path in files]
    width = max(w for w, _h, _r in images) if images else 1
    height = sum(h for _w, h, _r in images) if images else 1
    rows = [[(0, 0, 0, 0)] * width for _ in range(height)]
    placements = []
    y = 0
    for (w, h, img), path in zip(images, files):
        for ry in range(h):
            row = rows[y + ry]
            for rx in range(w):
                row[rx] = img[ry][rx]
        placements.append((path, 0, y, w, h))
        y += h
    return (width, height, rows, placements)


def build_geometry(pack, chain, name):
    """(geometry_dict, atlas_png_bytes) for one Bedrock attachable."""
    files = pack.used_texture_files(chain)
    atlas_w, atlas_h, atlas_rows, placements = build_atlas(pack, files)
    place = {p[0]: p for p in placements}
    texsize = chain.texture_size or [16, 16]

    cubes = []
    lo = [1e9, 1e9, 1e9]
    hi = [-1e9, -1e9, -1e9]
    for el in chain.elements or []:
        frm, to = el.get('from'), el.get('to')
        if not frm or not to:
            continue
        size = [to[0] - frm[0], to[1] - frm[1], to[2] - frm[2]]
        origin = [8 - to[0], frm[1], frm[2] - 8]
        cube = {'origin': [round(v, 5) for v in origin],
                'size': [round(v, 5) for v in size]}
        erot = el.get('rotation') or {}
        if erot.get('axis') and erot.get('angle'):
            axis, angle = erot['axis'], float(erot['angle'])
            o = erot.get('origin', [0, 0, 0])
            pivot = [8 - o[0], o[1], o[2] - 8]
            bedrock_angle = -angle if axis == 'x' else (-angle if axis == 'z' else angle)
            rot = [0.0, 0.0, 0.0]
            rot['xyz'.index(axis)] = bedrock_angle
            cube['pivot'] = [round(v, 5) for v in pivot]
            cube['rotation'] = rot
        uv_faces = {}
        for fname in ('north', 'south', 'east', 'west', 'up', 'down'):
            face = el.get('faces', {}).get(fname)
            if face is None:
                continue
            t = face.get('texture', '')
            if isinstance(t, str) and t.startswith('#'):
                t = chain.textures.get(t[1:], '')
            path = None
            if t and t not in ('missing', 'missingno'):
                path = pack.resolve_texture(t, pack.load_model(chain.model_id).get('_ns', 'minecraft')
                                            if pack.load_model(chain.model_id) else 'minecraft')
            if path is None or path not in place:
                path = files[0]
            ax, ay, sub_w, sub_h = place[path][1], place[path][2], place[path][3], place[path][4]
            u1, v1, u2, v2 = _face_uv_rect(face, texsize)
            uv_faces[fname] = {
                'uv': [round(ax + u1 * sub_w, 4), round(ay + v1 * sub_h, 4)],
                'uv_size': [round((u2 - u1) * sub_w, 4), round((v2 - v1) * sub_h, 4)],
            }
        cube['uv'] = uv_faces
        cubes.append(cube)
        for i in range(3):
            lo[i] = min(lo[i], origin[i])
            hi[i] = max(hi[i], origin[i] + size[i])

    span_x = max(1.0, hi[0] - lo[0])
    span_y = max(1.0, hi[1] - lo[1])
    span_z = max(1.0, hi[2] - lo[2])
    geo = {
        'format_version': GEO_FORMAT,
        'minecraft:geometry': [{
            'description': {
                'identifier': 'geometry.altarsmp.%s' % name,
                'texture_width': atlas_w,
                'texture_height': atlas_h,
                'visible_bounds_width': round(max(span_x, span_z) / 16.0 + 1.0, 3),
                'visible_bounds_height': round(span_y / 16.0 + 1.0, 3),
                'visible_bounds_offset': [0, round((lo[1] + hi[1]) / 32.0, 3), 0],
            },
            'bones': [{'name': 'root', 'pivot': [0, 0, 0], 'cubes': cubes}],
        }],
    }
    return geo, encode_png(atlas_w, atlas_h, atlas_rows)
