"""PNG decode/encode for the AltarSMP crossplay tooling.

Pure python, stdlib only (zlib + struct) - no PIL, no numpy. The decoder is
deliberately narrow: it supports exactly what AltarSMP-ResourcePack.zip contains
(8-bit grayscale / RGB / RGBA / palette, 1/2/4/8-bit palette expansion, tRNS,
no interlace) and rejects anything else with a precise error, so a surprise
format fails loudly instead of silently corrupting an icon.

Images are represented as (width, height, rows) with rows a list of height
lists of (r, g, b, a) tuples, row 0 the top scanline.
"""

import struct
import zlib

SIGNATURE = b'\x89PNG\r\n\x1a\n'


class PngError(ValueError):
    pass


# ---------------------------------------------------------------- decode

_PALETTE_BITS = (1, 2, 4, 8)
_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _unpack_subbyte(row, bits, width):
    """Expand a 1/2/4-bit palette scanline into one index per pixel."""
    out = []
    per_byte = 8 // bits
    mask = (1 << bits) - 1
    for byte in row:
        for i in range(per_byte):
            if len(out) >= width:
                break
            shift = 8 - bits * (i + 1)
            out.append((byte >> shift) & mask)
    return out


def decode_png(data):
    """Decode a PNG blob to (width, height, rows). Raises PngError."""
    if not data.startswith(SIGNATURE):
        raise PngError('not a PNG (bad signature)')
    pos = 8
    ihdr = None
    palette = None
    trns = None
    idat = bytearray()
    while pos + 8 <= len(data):
        length, ctype = struct.unpack('>I4s', data[pos:pos + 8])
        pos += 8
        body = data[pos:pos + length]
        pos += length + 4  # crc
        if ctype == b'IHDR':
            ihdr = struct.unpack('>IIBBBBB', body)
        elif ctype == b'PLTE':
            palette = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif ctype == b'tRNS':
            trns = body
        elif ctype == b'IDAT':
            idat += body
        elif ctype == b'IEND':
            break
    if ihdr is None:
        raise PngError('no IHDR')
    width, height, depth, colortype, _comp, _filt, interlace = ihdr
    if interlace != 0:
        raise PngError('interlaced PNG not supported')
    if colortype not in _CHANNELS:
        raise PngError('unsupported colour type %d' % colortype)
    if colortype == 3:
        if depth not in _PALETTE_BITS:
            raise PngError('unsupported palette bit depth %d' % depth)
        if palette is None:
            raise PngError('palette PNG without PLTE')
    elif depth != 8:
        raise PngError('unsupported bit depth %d for colour type %d' % (depth, colortype))

    channels = _CHANNELS[colortype]
    bpp = max(1, channels * depth // 8)
    stride = (width * channels * depth + 7) // 8
    raw = zlib.decompress(bytes(idat))
    expected = (stride + 1) * height
    if len(raw) < expected:
        raise PngError('truncated image data')

    # unfilter scanlines in place
    out = bytearray(expected)
    prev_start = None
    for y in range(height):
        start = y * (stride + 1)
        filt = raw[start]
        line = bytearray(raw[start + 1:start + 1 + stride])
        if filt == 1:  # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filt == 2:  # Up
            if prev_start is not None:
                for i in range(stride):
                    line[i] = (line[i] + out[prev_start + i]) & 0xFF
        elif filt == 3:  # Average
            if prev_start is not None:
                for i in range(stride):
                    left = line[i - bpp] if i >= bpp else 0
                    line[i] = (line[i] + ((left + out[prev_start + i]) >> 1)) & 0xFF
            else:
                for i in range(bpp, stride):
                    line[i] = (line[i] + (line[i - bpp] >> 1)) & 0xFF
        elif filt == 4:  # Paeth
            if prev_start is not None:
                for i in range(stride):
                    left = line[i - bpp] if i >= bpp else 0
                    ul = out[prev_start + i - bpp] if i >= bpp else 0
                    up = out[prev_start + i]
                    line[i] = (line[i] + _paeth(left, up, ul)) & 0xFF
            else:
                for i in range(bpp, stride):
                    line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filt != 0:
            raise PngError('unknown scanline filter %d' % filt)
        out[start + 1:start + 1 + stride] = line
        prev_start = start

    rows = []
    for y in range(height):
        base = y * (stride + 1) + 1
        line = out[base:base + stride]
        row = []
        if colortype == 3:
            idx = line if depth == 8 else _unpack_subbyte(line, depth, width)
            for x in range(width):
                rgb = palette[idx[x]]
                alpha = trns[idx[x]] if trns and idx[x] < len(trns) else 255
                row.append((rgb[0], rgb[1], rgb[2], alpha))
        elif colortype == 0:
            for x in range(width):
                g = line[x]
                row.append((g, g, g, 255))
        elif colortype == 2:
            for x in range(width):
                row.append((line[x * 3], line[x * 3 + 1], line[x * 3 + 2], 255))
        elif colortype == 4:
            for x in range(width):
                g = line[x * 2]
                row.append((g, g, g, line[x * 2 + 1]))
        else:  # 6 - RGBA
            for x in range(width):
                row.append((line[x * 4], line[x * 4 + 1], line[x * 4 + 2], line[x * 4 + 3]))
        rows.append(row)
    return width, height, rows


def png_size(data):
    """(width, height) straight from the IHDR - cheap header peek."""
    if not data.startswith(SIGNATURE) or len(data) < 24:
        raise PngError('not a PNG')
    return struct.unpack('>II', data[16:24])


# ---------------------------------------------------------------- encode

def encode_png(width, height, rows):
    """Encode (width, height, rows) RGBA as an 8-bit PNG (filter 0, level 9)."""
    if len(rows) != height or any(len(r) != width for r in rows):
        raise PngError('row geometry mismatch')
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type None
        for px in row:
            raw += bytes((px[0] & 0xFF, px[1] & 0xFF, px[2] & 0xFF, px[3] & 0xFF))
    comp = zlib.compress(bytes(raw), 9)

    def chunk(ctype, body):
        return struct.pack('>I', len(body)) + ctype + body + struct.pack('>I', zlib.crc32(ctype + body) & 0xFFFFFFFF)

    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    return SIGNATURE + chunk(b'IHDR', ihdr) + chunk(b'IDAT', comp) + chunk(b'IEND', b'')
