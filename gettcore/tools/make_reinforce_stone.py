"""Generates assets/gettcore/textures/item/reinforce_stone.png (16x16): a grey stone with orange/gold streaks.

Run from anywhere: python gettcore/tools/make_reinforce_stone.py
Uses PIL when installed, otherwise writes the PNG by hand with zlib.
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "gettcore", "textures", "item",
                   "reinforce_stone.png")

T = (0, 0, 0, 0)
O = (34, 30, 30, 255)       # outline
D = (78, 76, 80, 255)       # dark stone
M = (118, 116, 120, 255)    # mid stone
L = (158, 156, 160, 255)    # light stone
H = (196, 194, 198, 255)    # highlight
R = (196, 92, 18, 255)      # deep orange
G = (238, 150, 34, 255)     # orange
Y = (255, 214, 92, 255)     # gold spark

# 16x16 pixel map; one character per pixel.
ROWS = [
    "................",
    "................",
    ".....OOOOOO.....",
    "...OOLHHLLMOO...",
    "..OLHHLMMGMMDO..",
    "..OHLMMMGYMMDO..",
    ".OLLMGMMRGMMDDO.",
    ".OLMMYGMMMMRDDO.",
    ".OMMMMRGGMMGDDO.",
    ".OMMDMMMRGGYDDO.",
    ".OMDDMMMMMRGDDO.",
    "..OMDGRMMMMDDO..",
    "..ODDDGYMMDDDO..",
    "...OODDDDDDOO...",
    ".....OOOOOO.....",
    "................",
]
PALETTE = {".": T, "O": O, "D": D, "M": M, "L": L, "H": H, "R": R, "G": G, "Y": Y}


def pixels():
    assert len(ROWS) == 16 and all(len(r) == 16 for r in ROWS)
    return [[PALETTE[c] for c in row] for row in ROWS]


def write_with_pil(px, path):
    from PIL import Image
    img = Image.new("RGBA", (16, 16))
    img.putdata([p for row in px for p in row])
    img.save(path)


def write_raw(px, path):
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *p) for p in row) for row in px)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def main():
    px = pixels()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    try:
        write_with_pil(px, OUT)
    except ImportError:
        write_raw(px, OUT)
    print("wrote", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
