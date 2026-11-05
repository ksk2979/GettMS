# 귀환석 마법진 텍스처 생성. 사용: python -I gettcore/tools/make-magic-circle.py
import math, pathlib
from PIL import Image, ImageDraw, ImageFilter

OUT = pathlib.Path(__file__).resolve().parents[1] / "src/main/resources/assets/gettcore/textures/effect/magic_circle.png"
S = 512
C = S / 2
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
MAIN = (170, 220, 255, 255)
SOFT = (120, 170, 255, 200)

def ring(r, w, col=MAIN):
    d.ellipse([C - r, C - r, C + r, C + r], outline=col, width=w)

def poly(n, r, rot, w, col=MAIN):
    pts = [(C + math.cos(rot + i * 2 * math.pi / n) * r, C + math.sin(rot + i * 2 * math.pi / n) * r) for i in range(n)]
    d.polygon(pts, outline=col, width=w)

# 바깥 이중 링 + 룬 눈금
ring(248, 4); ring(232, 2)
for i in range(48):
    a = i * 2 * math.pi / 48
    r1, r2 = (236, 244) if i % 4 else (233, 247)
    d.line([(C + math.cos(a) * r1, C + math.sin(a) * r1), (C + math.cos(a) * r2, C + math.sin(a) * r2)], fill=MAIN, width=3 if i % 4 == 0 else 2)
# 룬 느낌의 작은 글리프(사각/삼각 번갈아)
for i in range(24):
    a = i * 2 * math.pi / 24 + math.pi / 24
    x, y = C + math.cos(a) * 212, C + math.sin(a) * 212
    if i % 2:
        d.rectangle([x - 5, y - 5, x + 5, y + 5], outline=SOFT, width=2)
    else:
        d.polygon([(x, y - 6), (x - 6, y + 5), (x + 6, y + 5)], outline=SOFT, width=2)
ring(196, 3)
# 육망성 + 안쪽 링
poly(3, 190, -math.pi / 2, 4)
poly(3, 190, math.pi / 2, 4)
ring(96, 3); ring(84, 2, SOFT)
# 안쪽 작은 삼각형들
poly(6, 84, 0, 2, SOFT)
poly(3, 60, -math.pi / 2, 3)
ring(24, 3)
d.ellipse([C - 10, C - 10, C + 10, C + 10], fill=MAIN)

# 글로우: 흐린 복사본을 아래에 깐다
glow = img.filter(ImageFilter.GaussianBlur(6))
glow = Image.eval(glow, lambda v: v)  # 복사
r, g, b, a = glow.split()
a = a.point(lambda v: min(255, int(v * 0.9)))
glow = Image.merge("RGBA", (r, g, b, a))
final = Image.alpha_composite(glow, img)
OUT.parent.mkdir(parents=True, exist_ok=True)
final.save(OUT)
print("wrote", OUT, final.size)
