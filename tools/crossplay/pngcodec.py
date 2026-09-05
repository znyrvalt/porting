"""Minimal PNG reader/writer built on struct + zlib (no third-party imports).

Decodes the colour types the AltarSMP packs actually use (greyscale,
greyscale+alpha, truecolour, indexed with 1/2/4/8-bit samples and RGBA) into a
flat RGBA8 pixel buffer, and writes back non-interlaced RGBA8 PNGs.
"""

from __future__ import annotations

import struct
import zlib

PNG_SIG = b"\x89PNG\r\n\x1a\n"


class PngError(RuntimeError):
    pass


class Image:
    __slots__ = ("width", "height", "pixels")

    def __init__(self, width: int, height: int, pixels: bytearray):
        self.width = width
        self.height = height
        self.pixels = pixels  # RGBA8, row-major, len == w*h*4

    def pixel(self, x: int, y: int):
        if x < 0 or y < 0 or x >= self.width or y >= self.height:
            return (0, 0, 0, 0)
        i = (y * self.width + x) * 4
        p = self.pixels
        return (p[i], p[i + 1], p[i + 2], p[i + 3])

    def set_pixel(self, x: int, y: int, rgba) -> None:
        i = (y * self.width + x) * 4
        self.pixels[i:i + 4] = bytes(rgba)

    def crop(self, x: int, y: int, w: int, h: int) -> "Image":
        out = Image(w, h, bytearray(w * h * 4))
        for row in range(h):
            src = ((y + row) * self.width + x) * 4
            dst = row * w * 4
            out.pixels[dst:dst + w * 4] = self.pixels[src:src + w * 4]
        return out

    def is_blank(self) -> bool:
        return all(self.pixels[i] == 0 for i in range(3, len(self.pixels), 4))


def _chunks(data: bytes):
    if data[:8] != PNG_SIG:
        raise PngError("not a PNG")
    off = 8
    while off < len(data):
        (length,) = struct.unpack(">I", data[off:off + 4])
        ctype = data[off + 4:off + 8]
        body = data[off + 8:off + 8 + length]
        off += 12 + length
        yield ctype, body


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _unfilter(raw: bytes, width: int, height: int, bpp: int, stride: int) -> bytearray:
    out = bytearray(height * stride)
    prev = bytearray(stride)
    pos = 0
    for y in range(height):
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if ftype == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                upleft = prev[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(left, prev[i], upleft)) & 0xFF
        elif ftype != 0:
            raise PngError("bad filter %d" % ftype)
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return out


def _samples(line: bytes, depth: int, count: int):
    if depth == 8:
        return list(line[:count])
    if depth == 16:
        return [line[i * 2] for i in range(count)]
    per = 8 // depth
    mask = (1 << depth) - 1
    vals = []
    for i in range(count):
        byte = line[i // per]
        shift = 8 - depth * (i % per + 1)
        vals.append((byte >> shift) & mask)
    return vals


def decode(data: bytes) -> Image:
    width = height = depth = ctype = None
    idat = bytearray()
    palette = b""
    trns = b""
    for name, body in _chunks(data):
        if name == b"IHDR":
            width, height, depth, ctype, _comp, _filt, interlace = struct.unpack(">IIBBBBB", body)
            if interlace:
                raise PngError("interlaced PNGs are not supported")
        elif name == b"PLTE":
            palette = body
        elif name == b"tRNS":
            trns = body
        elif name == b"IDAT":
            idat += body
        elif name == b"IEND":
            break
    if width is None:
        raise PngError("missing IHDR")
    raw = zlib.decompress(bytes(idat))
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = channels * depth
    stride = (width * bits + 7) // 8
    bpp = max(1, bits // 8)
    lines = _unfilter(raw, width, height, bpp, stride)
    px = bytearray(width * height * 4)
    scale = 255 // ((1 << depth) - 1) if depth < 8 else 1
    for y in range(height):
        line = lines[y * stride:(y + 1) * stride]
        vals = _samples(line, depth, width * channels)
        for x in range(width):
            o = (y * width + x) * 4
            if ctype == 0:
                g = vals[x] * scale
                px[o:o + 4] = bytes((g, g, g, 255))
            elif ctype == 4:
                g = vals[x * 2] * scale
                px[o:o + 4] = bytes((g, g, g, vals[x * 2 + 1] * scale))
            elif ctype == 2:
                r, g, b = vals[x * 3:x * 3 + 3]
                px[o:o + 4] = bytes((r * scale, g * scale, b * scale, 255))
            elif ctype == 6:
                r, g, b, a = vals[x * 4:x * 4 + 4]
                px[o:o + 4] = bytes((r * scale, g * scale, b * scale, a * scale))
            else:
                idx = vals[x]
                r = palette[idx * 3] if idx * 3 + 2 < len(palette) else 0
                g = palette[idx * 3 + 1] if idx * 3 + 2 < len(palette) else 0
                b = palette[idx * 3 + 2] if idx * 3 + 2 < len(palette) else 0
                a = trns[idx] if idx < len(trns) else 255
                px[o:o + 4] = bytes((r, g, b, a))
    return Image(width, height, px)


def encode(img: Image) -> bytes:
    raw = bytearray()
    stride = img.width * 4
    for y in range(img.height):
        raw.append(0)
        raw += img.pixels[y * stride:(y + 1) * stride]
    comp = zlib.compressobj(level=9, wbits=15, memLevel=9, strategy=zlib.Z_DEFAULT_STRATEGY)
    body = comp.compress(bytes(raw)) + comp.flush()

    def chunk(name: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + name + payload
                + struct.pack(">I", zlib.crc32(name + payload) & 0xFFFFFFFF))

    return (PNG_SIG
            + chunk(b"IHDR", struct.pack(">IIBBBBB", img.width, img.height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", body)
            + chunk(b"IEND", b""))


def blank(width: int, height: int) -> Image:
    return Image(width, height, bytearray(width * height * 4))


def read(path: str) -> Image:
    with open(path, "rb") as fh:
        return decode(fh.read())


def header(data: bytes):
    """(width, height, bit_depth, colour_type) without decoding pixels."""
    for name, body in _chunks(data):
        if name == b"IHDR":
            w, h, d, c = struct.unpack(">IIBB", body[:10])
            return w, h, d, c
    raise PngError("missing IHDR")
