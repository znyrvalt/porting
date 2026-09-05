"""Icon rasterizer for Java item models (pure python, stdlib only).

Renders the model a Java entry dispatches to into a square icon texture for the
Bedrock pack. One orthographic "gui" view of the model's element boxes, painter-
sorted, per-face UV sampling from the referenced textures.

Approximations, on purpose: one orthographic view (no perspective), painter's
algorithm instead of a z-buffer, no lighting. What must NOT be approximate is
determinism: same model in, same pixels out, every run.
"""

import math

from pngio import encode_png

ICON_SIZE = 128
# The canvas is the projection's coordinate basis: it spans CANVAS_UNITS
# canvas-relative units across the icon, whatever the model's own grid is.
CANVAS_UNITS = 16.0
# Uniform border kept free on every side; also the headroom auto-fit targets.
FIT_MARGIN = 4

# canonical quad corners per face, model space, order: top-left, top-right,
# bottom-right, bottom-left "as seen from outside the box"
_FACE_CORNERS = {
    'north': lambda x1, y1, z1, x2, y2, z2: [(x2, y2, z1), (x1, y2, z1), (x1, y1, z1), (x2, y1, z1)],
    'south': lambda x1, y1, z1, x2, y2, z2: [(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)],
    'west': lambda x1, y1, z1, x2, y2, z2: [(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)],
    'east': lambda x1, y1, z1, x2, y2, z2: [(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)],
    'up': lambda x1, y1, z1, x2, y2, z2: [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)],
    'down': lambda x1, y1, z1, x2, y2, z2: [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)],
}
_FACE_NAMES = ('north', 'south', 'west', 'east', 'up', 'down')


def _rotate(v, axis, angle_deg, origin):
    """Right-handed rotation of v about `axis` through `origin` (degrees)."""
    x, y, z = v[0] - origin[0], v[1] - origin[1], v[2] - origin[2]
    a = math.radians(angle_deg)
    ca, sa = math.cos(a), math.sin(a)
    if axis == 'x':
        y, z = y * ca - z * sa, y * sa + z * ca
    elif axis == 'y':
        x, z = x * ca + z * sa, -x * sa + z * ca
    elif axis == 'z':
        x, y = x * ca - y * sa, x * sa + y * ca
    return (x + origin[0], y + origin[1], z + origin[2])


def _gui_apply(v, gui):
    """display.gui: scale, then rotation, then translation.

    The rotation is applied in Z, Y, X vector order - the order vanilla's
    ItemTransform ends up with through JOML's rotationXYZ quaternion.
    """
    rot = gui.get('rotation', [0, 0, 0]) if gui else [0, 0, 0]
    scale = gui.get('scale', [1.0, 1.0, 1.0]) if gui else [1.0, 1.0, 1.0]
    trans = gui.get('translation', [0.0, 0.0, 0.0]) if gui else [0.0, 0.0, 0.0]
    x, y, z = v[0] * scale[0], v[1] * scale[1], v[2] * scale[2]
    for axis, angle in (('z', rot[2]), ('y', rot[1]), ('x', rot[0])):
        if angle:
            x, y, z = _rotate((x, y, z), axis, angle, (0, 0, 0))
    return (x + trans[0], y + trans[1], z + trans[2])


def build_quads(pack, chain):
    """All faces of the model as transformed quads.

    Each quad: (points, uv_corners, texture_rows, tex_w, tex_h, depth) where
    points are (x, y, z) after element rotation and the gui display transform,
    and uv_corners are normalized sample coordinates (against a 16x16 basis,
    the classic item-model grid).
    """
    quads = []
    files = pack.used_texture_files(chain)
    textures = {}
    for path in files:
        w, h, rows = pack.load_texture(path)
        textures[path] = (w, h, rows)

    def texture_for(face):
        t = face.get('texture', '')
        if isinstance(t, str) and t.startswith('#'):
            t = chain.textures.get(t[1:], '')
        if not t or t in ('missing', 'missingno'):
            return files[0] if files else None
        got = pack.resolve_texture(t, pack.load_model(chain.model_id).get('_ns', 'minecraft')
                                   if pack.load_model(chain.model_id) else 'minecraft')
        return got if got in textures else (files[0] if files else None)

    for el in chain.elements or []:
        frm, to = el.get('from'), el.get('to')
        if not frm or not to:
            continue
        erot = el.get('rotation') or {}
        eorigin = erot.get('origin', [0, 0, 0])
        eaxis, eangle = erot.get('axis'), erot.get('angle', 0)
        for fname in _FACE_NAMES:
            face = el.get('faces', {}).get(fname)
            if face is None:
                continue
            uv = face.get('uv', [0, 0, 16, 16])
            # normalize against the classic 16x16 item grid
            uvc = [(uv[0] / 16.0, uv[1] / 16.0), (uv[2] / 16.0, uv[1] / 16.0),
                   (uv[2] / 16.0, uv[3] / 16.0), (uv[0] / 16.0, uv[3] / 16.0)]
            k = (face.get('rotation', 0) // 90) % 4
            if k:
                uvc = uvc[-k:] + uvc[:-k]
            corners = _FACE_CORNERS[fname](frm[0], frm[1], frm[2], to[0], to[1], to[2])
            pts = []
            for c in corners:
                v = c
                if eaxis and eangle:
                    v = _rotate(v, eaxis, eangle, eorigin)
                v = _gui_apply(v, chain.gui)
                pts.append(v)
            tpath = texture_for(face)
            if tpath is None:
                continue
            tw, th, trows = textures[tpath]
            # depth of the element's centroid: all faces of one cube share it,
            # so the painter's sort keeps a cube's faces together
            depth = (frm[0] + to[0] + frm[1] + to[1] + frm[2] + to[2]) / 6.0
            quads.append((pts, uvc, trows, tw, th, depth))
    return quads


def _project(quads):
    """Model-space quads -> canvas pixels.

    The projection is computed in canvas-relative units: the base scale maps
    the canvas (CANVAS_UNITS across) onto the icon, independent of any
    model's own grid. The model's projected bounds are then measured against
    that canvas, and a model whose bounds exceed the drawable area is
    auto-fitted: one uniform scale factor shrinks it to fit (aspect ratio
    preserved - never stretched, never clipped), while everything that
    already fits keeps the canvas base scale.
    """
    xs = [p[0] for q in quads for p in q[0]]
    ys = [p[1] for q in quads for p in q[0]]
    if not xs:
        return []
    cx, cy = (min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0
    drawable = ICON_SIZE - 2 * FIT_MARGIN
    # canvas-relative base scale: CANVAS_UNITS across the drawable area
    scale = drawable / CANVAS_UNITS
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    if span * scale > drawable:
        # auto-fit: uniform scale-to-fit, preserving aspect
        scale = drawable / span
    out = []
    for pts, uvc, trows, tw, th, depth in quads:
        px = [((p[0] - cx) * scale + ICON_SIZE / 2.0, ICON_SIZE / 2.0 - (p[1] - cy) * scale)
              for p in pts]
        out.append((px, uvc, trows, tw, th, depth))
    return out


def _rasterize(canvas, quads):
    """Painter's algorithm: far quads first, nearest-overwrites, alpha blend."""
    size = ICON_SIZE
    for px, uvc, trows, tw, th, _depth in quads:
        for (ax, ay, bx, by, cx, cy, u_a, v_a, u_b, v_b, u_c, v_c) in _triangles(px, uvc):
            _triangle(canvas, size, ax, ay, bx, by, cx, cy,
                      u_a, v_a, u_b, v_b, u_c, v_c, trows, tw, th)


def _triangles(px, uvc):
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = px
    (u0, v0), (u1, v1), (u2, v2), (u3, v3) = uvc
    yield (x0, y0, x1, y1, x2, y2, u0, v0, u1, v1, u2, v2)
    yield (x0, y0, x2, y2, x3, y3, u0, v0, u2, v2, u3, v3)


def _triangle(canvas, size, ax, ay, bx, by, cx, cy,
              u_a, v_a, u_b, v_b, u_c, v_c, trows, tw, th):
    min_x = max(0, int(min(ax, bx, cx)))
    max_x = min(size - 1, int(max(ax, bx, cx)) + 1)
    min_y = max(0, int(min(ay, by, cy)))
    max_y = min(size - 1, int(max(ay, by, cy)) + 1)
    if min_x > max_x or min_y > max_y:
        return
    det = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    if det == 0:
        return
    for py in range(min_y, max_y + 1):
        sy = py + 0.5
        row = canvas[py]
        for pxx in range(min_x, max_x + 1):
            sx = pxx + 0.5
            w1 = ((sx - ax) * (cy - ay) - (sy - ay) * (cx - ax)) / det
            w2 = ((bx - ax) * (sy - ay) - (by - ay) * (sx - ax)) / det
            if w1 < 0.0 or w2 < 0.0 or w1 + w2 > 1.0:
                continue
            w0 = 1.0 - w1 - w2
            # sample along the pixel row direction
            u = w0 * u_a + w1 * u_b + w2 * u_c
            v = w0 * v_a + w1 * v_b + w2 * v_c
            col = int(u * tw) % tw
            rowi = int((1.0 - v) * th) % th
            r, g, b, a = trows[rowi][col]
            if a == 0:
                continue
            if a >= 255:
                row[pxx] = (r, g, b, 255)
            else:
                dr, dg, db, da = row[pxx]
                out_a = a + da * (255 - a) // 255
                if out_a == 0:
                    row[pxx] = (0, 0, 0, 0)
                    continue
                out_r = (r * a + dr * da * (255 - a) // 255) // out_a
                out_g = (g * a + dg * da * (255 - a) // 255) // out_a
                out_b = (b * a + db * da * (255 - a) // 255) // out_a
                row[pxx] = (out_r, out_g, out_b, out_a)


def render_model(pack, chain):
    """Rasterize the model into an ICON_SIZE square RGBA icon (png bytes)."""
    canvas = [[(0, 0, 0, 0)] * ICON_SIZE for _ in range(ICON_SIZE)]
    quads = build_quads(pack, chain)
    projected = _project(quads)
    projected.sort(key=lambda q: q[5])
    _rasterize(canvas, projected)
    return encode_png(ICON_SIZE, ICON_SIZE, canvas)


def render_texture(pack, archive_path):
    """Flat-model icon: nearest-neighbour scale of the referenced texture."""
    w, h, rows = pack.load_texture(archive_path)
    size = ICON_SIZE
    canvas = []
    for y in range(size):
        srow = rows[min(h - 1, y * h // size)]
        row = []
        for x in range(size):
            row.append(srow[min(w - 1, x * w // size)])
        canvas.append(row)
    return encode_png(size, size, canvas)
