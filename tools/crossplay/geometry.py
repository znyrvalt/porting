"""Java model elements -> Bedrock ``geometry.json`` cubes.

Bedrock cubes carry per-face UVs, which maps onto Java's per-face ``uv``
rectangles directly, so no atlas rebaking is needed.  Two differences matter:

* Java UVs are expressed in 0..16 layout units and are rescaled onto the real
  texture size.
* Bedrock will not render a zero-thickness cube.  Blockbench planes (a cube with
  ``from`` == ``to`` on one axis) are inflated to a 1/16 sliver, centred on the
  original plane, so they stay visible without moving.

Bedrock's coordinate origin sits at the model's pivot with Y up and Z inverted
relative to Java's element box, which is handled by ``_origin``.
"""

from __future__ import annotations

SLIVER = 1.0 / 16.0

FACE_MAP = {"north": "north", "south": "south", "east": "east", "west": "west",
            "up": "up", "down": "down"}


def _origin(frm, to):
    # Java x grows east, Bedrock x grows west: mirror x about the 8-unit centre.
    return [8.0 - to[0], frm[1], frm[2] - 8.0]


def convert_elements(model, texture_sizes, declared):
    """Return (cubes, texture_width, texture_height) for one Java model."""
    if not model.get("elements"):
        return [], 16, 16
    width, height = 16, 16
    if texture_sizes:
        width, height = texture_sizes
    sx = width / 16.0
    sy = height / 16.0

    cubes = []
    for element in model["elements"]:
        frm = [float(v) for v in element.get("from", [0, 0, 0])]
        to = [float(v) for v in element.get("to", [16, 16, 16])]
        size = [to[i] - frm[i] for i in range(3)]
        for i in range(3):
            if abs(size[i]) < 1e-6:
                frm[i] -= SLIVER / 2.0
                to[i] += SLIVER / 2.0
                size[i] = SLIVER
        cube = {"origin": _origin(frm, to), "size": [round(v, 5) for v in size]}
        rotation = element.get("rotation")
        if rotation:
            axis = rotation.get("axis", "y")
            angle = float(rotation.get("angle", 0.0))
            origin = [float(v) for v in rotation.get("origin", [8, 8, 8])]
            pivot = [8.0 - origin[0], origin[1], origin[2] - 8.0]
            cube["pivot"] = [round(v, 5) for v in pivot]
            # x/y rotate the opposite way once x and z are mirrored
            vector = {"x": [-angle, 0.0, 0.0], "y": [0.0, -angle, 0.0], "z": [0.0, 0.0, angle]}[axis]
            cube["rotation"] = [round(v, 5) for v in vector]
        uv = {}
        for name, face in (element.get("faces") or {}).items():
            bedrock = FACE_MAP.get(name)
            if bedrock is None:
                continue
            rect = face.get("uv")
            if rect is None:
                continue
            u0, v0, u1, v1 = [float(v) for v in rect]
            uv[bedrock] = {
                "uv": [round(min(u0, u1) * sx, 5), round(min(v0, v1) * sy, 5)],
                "uv_size": [round(abs(u1 - u0) * sx, 5), round(abs(v1 - v0) * sy, 5)],
            }
        if uv:
            cube["uv"] = uv
        cubes.append(cube)
    return cubes, width, height


def geometry(identifier, cubes, width, height):
    return {
        "format_version": "1.16.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": identifier,
                "texture_width": int(width),
                "texture_height": int(height),
                "visible_bounds_width": 3,
                "visible_bounds_height": 3,
                "visible_bounds_offset": [0, 1, 0],
            },
            "bones": [{"name": "root", "pivot": [0, 0, 0], "cubes": cubes}],
        }],
    }


def humanoid_geometry(identifier):
    """Box-UV humanoid geometry for worn armour attachables (64x32 skins)."""
    def box(name, pivot, origin, size, uv, inflate=None):
        cube = {"origin": origin, "size": size, "uv": uv}
        if inflate:
            cube["inflate"] = inflate
        return {"name": name, "pivot": pivot, "cubes": [cube]}

    return {
        "format_version": "1.16.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": identifier,
                "texture_width": 64,
                "texture_height": 32,
                "visible_bounds_width": 2,
                "visible_bounds_height": 3,
                "visible_bounds_offset": [0, 1.5, 0],
            },
            "bones": [
                {"name": "root", "pivot": [0, 0, 0]},
                {"name": "waist", "parent": "root", "pivot": [0, 12, 0]},
                {"name": "body", "parent": "waist", "pivot": [0, 24, 0],
                 "cubes": [{"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16], "inflate": 1.02}]},
                {"name": "head", "parent": "body", "pivot": [0, 24, 0],
                 "cubes": [{"origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [0, 0], "inflate": 1.02}]},
                {"name": "leftArm", "parent": "body", "pivot": [5, 22, 0],
                 "cubes": [{"origin": [4, 12, -2], "size": [4, 12, 4], "uv": [40, 16], "inflate": 1.02}], "mirror": True},
                {"name": "rightArm", "parent": "body", "pivot": [-5, 22, 0],
                 "cubes": [{"origin": [-8, 12, -2], "size": [4, 12, 4], "uv": [40, 16], "inflate": 1.02}]},
                {"name": "leftLeg", "parent": "root", "pivot": [1.9, 12, 0],
                 "cubes": [{"origin": [-0.1, 0, -2], "size": [4, 12, 4], "uv": [0, 16], "inflate": 0.52}], "mirror": True},
                {"name": "rightLeg", "parent": "root", "pivot": [-1.9, 12, 0],
                 "cubes": [{"origin": [-3.9, 0, -2], "size": [4, 12, 4], "uv": [0, 16], "inflate": 0.52}]},
            ],
        }],
    }
