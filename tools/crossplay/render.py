"""Orthographic software renderer for Java item models -> Bedrock inventory icons.

Bedrock takes a flat sprite for the inventory slot, so every 3D Java model has
to be rasterised once at build time.  The renderer is deliberately small:

* the model's ``display.gui`` transform (translation, rotation, scale) is
  applied to each element, then the standard Java GUI view (30 deg down,
  225 deg around Y) unless the model overrides it;
* faces are drawn back-to-front (painter's algorithm) with bilinear sampling of
  the face's UV rectangle;
* the projection works in *canvas-relative* units: one model unit is
  ``canvas / 16``, and if the projected silhouette is larger than the slot the
  whole model is uniformly scaled down to fit rather than clipped;
* rendering runs at 4x supersample and is box-filtered down to 32x32.

If the GUI view produces nothing (flat planes lying in the ground plane, which
are edge-on from the GUI angle) the caller retries top-down, and finally falls
back to the model's own texture.
"""

from __future__ import annotations

import math

import pngcodec
from discovery import resolve_texture_ref

SUPERSAMPLE = 4
ICON_SIZE = 32


# ------------------------------------------------------------------ 3D helpers

def _matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _rot(axis, degrees):
    r = math.radians(degrees)
    c, s = math.cos(r), math.sin(r)
    if axis == "x":
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == "y":
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def _apply(m, v):
    return [sum(m[i][j] * v[j] for j in range(3)) for i in range(3)]


FACE_CORNERS = {
    # face -> the four cube corners, ordered to match Java's UV winding
    "north": ((1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)),
    "south": ((0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)),
    "west": ((0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)),
    "east": ((1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)),
    "up": ((0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)),
    "down": ((0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)),
}

FACE_NORMAL = {
    "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0),
    "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0),
}

FACE_SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def _default_gui_transform(display):
    gui = display.get("gui") if display else None
    if not gui:
        return {"rotation": [30.0, 225.0, 0.0], "translation": [0.0, 0.0, 0.0], "scale": [0.625, 0.625, 0.625]}
    return {
        "rotation": [float(v) for v in gui.get("rotation", [30.0, 225.0, 0.0])],
        "translation": [float(v) for v in gui.get("translation", [0.0, 0.0, 0.0])],
        "scale": [float(v) for v in gui.get("scale", [1.0, 1.0, 1.0])],
    }


def _element_matrix(element):
    """Blockbench per-element rotation (axis + angle around an origin)."""
    rotation = element.get("rotation")
    if not rotation:
        return None
    axis = rotation.get("axis", "y")
    angle = float(rotation.get("angle", 0.0))
    origin = [float(v) for v in rotation.get("origin", [8, 8, 8])]
    return _rot(axis, angle), origin


def _sample(image, u, v):
    """Bilinear sample in pixel coordinates; v grows downwards, as in Java."""
    u = min(max(u, 0.0), image.width - 1e-4)
    v = min(max(v, 0.0), image.height - 1e-4)
    x0, y0 = int(u), int(v)
    fx, fy = u - x0, v - y0
    x1 = min(x0 + 1, image.width - 1)
    y1 = min(y0 + 1, image.height - 1)
    out = [0.0, 0.0, 0.0, 0.0]
    for (px, py, w) in ((x0, y0, (1 - fx) * (1 - fy)), (x1, y0, fx * (1 - fy)),
                        (x0, y1, (1 - fx) * fy), (x1, y1, fx * fy)):
        pixel = image.pixel(px, py)
        for i in range(4):
            out[i] += pixel[i] * w
    return [int(round(c)) for c in out]


class Quad:
    __slots__ = ("points", "uvs", "image", "depth", "shade", "tint")

    def __init__(self, points, uvs, image, depth, shade):
        self.points = points
        self.uvs = uvs
        self.image = image
        self.depth = depth
        self.shade = shade


def build_quads(model, textures, view_rotation=None):
    """Project every element face into 2D screen space (model units).

    Returns the quad list plus the silhouette bounds, in *model units* where the
    model box is 0..16.  No rasterisation happens here so the caller can decide
    on the fit before paying for pixels.
    """
    display = _default_gui_transform(model.get("display") or {})
    rotation = view_rotation if view_rotation is not None else display["rotation"]
    view = _matmul(_matmul(_rot("x", rotation[0]), _rot("y", rotation[1])), _rot("z", rotation[2]))
    scale = display["scale"]
    translation = display["translation"]

    declared = model.get("texture_size") or [16, 16]
    quads = []
    for element in model.get("elements") or []:
        frm = [float(v) for v in element.get("from", [0, 0, 0])]
        to = [float(v) for v in element.get("to", [16, 16, 16])]
        local = _element_matrix(element)
        faces = element.get("faces") or {}
        for face_name, face in faces.items():
            corners = FACE_CORNERS.get(face_name)
            if corners is None:
                continue
            texture_ref = resolve_texture_ref(model, face.get("texture", "#0"))
            image = textures.get(texture_ref)
            if image is None:
                continue
            uv = face.get("uv")
            if uv is None:
                uv = _implicit_uv(face_name, frm, to)
            # Blockbench UVs are in 0..16 layout units; v already grows
            # downwards, so there is no flip.
            sx = image.width / 16.0
            sy = image.height / 16.0
            u0, v0, u1, v1 = [float(v) for v in uv]
            uv_px = [(u0 * sx, v0 * sy), (u1 * sx, v0 * sy), (u1 * sx, v1 * sy), (u0 * sx, v1 * sy)]
            uv_px = _rotate_uv(uv_px, int(face.get("rotation", 0) or 0))

            projected = []
            depth = 0.0
            for index, corner in enumerate(corners):
                point = [frm[i] + (to[i] - frm[i]) * corner[i] for i in range(3)]
                if local:
                    matrix, origin = local
                    point = [point[i] - origin[i] for i in range(3)]
                    point = _apply(matrix, point)
                    point = [point[i] + origin[i] for i in range(3)]
                point = [(point[i] - 8.0) * scale[i] + translation[i] for i in range(3)]
                point = _apply(view, point)
                projected.append((point[0], point[1]))
                depth += point[2]
            normal = _apply(view, FACE_NORMAL[face_name])
            if normal[2] > 0.0:
                # back face for this view; painter's algorithm still needs it if
                # the model is one-sided, so keep it but sort it behind.
                pass
            quads.append(Quad(projected, _face_uv_order(uv_px, corners, face_name),
                              image, depth / 4.0, FACE_SHADE.get(face_name, 0.8)))
    return quads


def _implicit_uv(face_name, frm, to):
    if face_name in ("up", "down"):
        return [frm[0], frm[2], to[0], to[2]]
    if face_name in ("north", "south"):
        return [frm[0], 16 - to[1], to[0], 16 - frm[1]]
    return [frm[2], 16 - to[1], to[2], 16 - frm[1]]


def _rotate_uv(uv_px, degrees):
    steps = (degrees // 90) % 4
    return uv_px[steps:] + uv_px[:steps]


def _face_uv_order(uv_px, corners, face_name):
    return uv_px


def bounds(quads):
    xs = [p[0] for q in quads for p in q.points]
    ys = [p[1] for q in quads for p in q.points]
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def rasterise(quads, size=ICON_SIZE, supersample=SUPERSAMPLE):
    """Draw quads back-to-front into an RGBA icon, auto-fitting the silhouette."""
    box = bounds(quads)
    if box is None:
        return None
    canvas = size * supersample
    unit = canvas / 16.0  # one model unit, canvas-relative
    min_x, min_y, max_x, max_y = box
    width = max(max_x - min_x, 1e-6)
    height = max(max_y - min_y, 1e-6)
    fit = 1.0
    if width * unit > canvas or height * unit > canvas:
        fit = min(canvas / (width * unit), canvas / (height * unit))
    centre_x = (min_x + max_x) / 2.0
    centre_y = (min_y + max_y) / 2.0

    def project(point):
        x = canvas / 2.0 + (point[0] - centre_x) * unit * fit
        y = canvas / 2.0 - (point[1] - centre_y) * unit * fit
        return x, y

    image = pngcodec.blank(canvas, canvas)
    for quad in sorted(quads, key=lambda q: q.depth):
        _fill_quad(image, [project(p) for p in quad.points], quad)
    return _downsample(image, size)


def _fill_quad(image, pts, quad):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    x0 = max(int(math.floor(min(xs))), 0)
    x1 = min(int(math.ceil(max(xs))), image.width - 1)
    y0 = max(int(math.floor(min(ys))), 0)
    y1 = min(int(math.ceil(max(ys))), image.height - 1)
    if x1 < x0 or y1 < y0:
        return
    # split the quad into two triangles and barycentric-interpolate the UVs
    tris = ((0, 1, 2), (0, 2, 3))
    for tri in tris:
        a, b, c = (pts[i] for i in tri)
        ua, ub, uc = (quad.uvs[i] for i in tri)
        det = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(det) < 1e-9:
            continue
        for py in range(y0, y1 + 1):
            for px in range(x0, x1 + 1):
                x = px + 0.5
                y = py + 0.5
                l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / det
                l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / det
                l3 = 1.0 - l1 - l2
                if l1 < -1e-6 or l2 < -1e-6 or l3 < -1e-6:
                    continue
                u = ua[0] * l1 + ub[0] * l2 + uc[0] * l3
                v = ua[1] * l1 + ub[1] * l2 + uc[1] * l3
                r, g, bl, alpha = _sample(quad.image, u, v)
                if alpha == 0:
                    continue
                shade = quad.shade
                src = (int(r * shade), int(g * shade), int(bl * shade), alpha)
                dst = image.pixel(px, py)
                if alpha >= 250 or dst[3] == 0:
                    image.set_pixel(px, py, src)
                else:
                    a0 = alpha / 255.0
                    out = [int(src[i] * a0 + dst[i] * (1 - a0)) for i in range(3)]
                    out.append(max(dst[3], alpha))
                    image.set_pixel(px, py, out)


def _downsample(image, size):
    factor = image.width // size
    out = pngcodec.blank(size, size)
    area = factor * factor
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for dy in range(factor):
                for dx in range(factor):
                    pr, pg, pb, pa = image.pixel(x * factor + dx, y * factor + dy)
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            if a == 0:
                continue
            out.set_pixel(x, y, (min(255, r // a), min(255, g // a), min(255, b // a), a // area))
    return out


def scale_to(image, size):
    """Nearest-then-average resize of a flat texture into a square icon."""
    out = pngcodec.blank(size, size)
    for y in range(size):
        for x in range(size):
            sx0 = x * image.width // size
            sx1 = max(sx0 + 1, (x + 1) * image.width // size)
            sy0 = y * image.height // size
            sy1 = max(sy0 + 1, (y + 1) * image.height // size)
            r = g = b = a = n = 0
            for sy in range(sy0, sy1):
                for sx in range(sx0, sx1):
                    pr, pg, pb, pa = image.pixel(sx, sy)
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
                    n += 1
            if a == 0 or n == 0:
                continue
            out.set_pixel(x, y, (min(255, r // a), min(255, g // a), min(255, b // a), a // n))
    return out


def render_icon(model, textures):
    """Render a model to a 32x32 icon; returns (image, icon_source)."""
    quads = build_quads(model, textures)
    if quads:
        image = rasterise(quads)
        if image is not None and not image.is_blank():
            return image, "gui-projection"
        # flat planes are edge-on from the GUI angle: look straight down
        quads = build_quads(model, textures, view_rotation=[90.0, 0.0, 0.0])
        image = rasterise(quads)
        if image is not None and not image.is_blank():
            return image, "top-down-projection"
    for ref in sorted(textures):
        image = textures[ref]
        if not image.is_blank():
            square = image if image.width == image.height else image.crop(0, 0, image.width, image.width)
            return scale_to(square, ICON_SIZE), "texture:%s" % ref
    return None, "none"
