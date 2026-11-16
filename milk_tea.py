# -*- coding: utf-8 -*-
"""珍珠奶茶插画生成器（Pillow + numpy 程序化绘制）"""
import math, random
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

S = 3                     # 超采样倍率
W, H = 640, 820
w, h = W * S, H * S

random.seed(11)
np.random.seed(11)

CX = 320
CUP_TOP, CUP_BOT = 252.0, 700.0
W_TOP, W_BOT = 302.0, 232.0
R_BOT = 52.0


def P(pts):
    return [(x * S, y * S) for x, y in pts]


def px(x, y):
    return (x * S, y * S)


def box(x0, y0, x1, y1):
    return [x0 * S, y0 * S, x1 * S, y1 * S]


def overlay(dst, layer, mask):
    """把 layer 按 mask 裁剪后叠加到 dst（保持 alpha 正确）"""
    out = layer.copy()
    out.putalpha(ImageChops.multiply(layer.getchannel("A"), mask))
    return Image.alpha_composite(dst, out)


# ---------------------------------------------------------------- 杯型轮廓
def cup_width(y):
    f = (y - CUP_TOP) / (CUP_BOT - CUP_TOP)
    return W_TOP + (W_BOT - W_TOP) * f


def cup_profile(cx, top, bottom, w_top, w_bot, rr, inset=0.0, n=64):
    top += inset
    bottom -= inset
    wt = w_top - 2 * inset
    wb = w_bot - 2 * inset
    height = bottom - top
    yb = bottom - rr

    def width_at(y):
        f = (y - top) / height
        return wt + (wb - wt) * f

    pts = []
    for i in range(n + 1):                      # 左壁
        y = top + (yb - top) * i / n
        pts.append((cx - width_at(y) / 2, y))
    xl = cx - width_at(yb) / 2
    xr = cx + width_at(yb) / 2
    for i in range(1, 26):                      # 左下圆角
        th = math.radians(180 - 90 * i / 25)
        pts.append((xl + rr + rr * math.cos(th), yb + rr * math.sin(th)))
    xlc, xrc = xl + rr, xr - rr
    for i in range(1, 14):                      # 底边
        pts.append((xlc + (xrc - xlc) * i / 13, bottom))
    for i in range(0, 26):                      # 右下圆角
        th = math.radians(90 - 90 * i / 25)
        pts.append((xrc + rr * math.cos(th), yb + rr * math.sin(th)))
    for i in range(n + 1):                      # 右壁向上
        y = yb - (yb - top) * i / n
        pts.append((cx + width_at(y) / 2, y))
    return pts


CUP_PTS = cup_profile(CX, CUP_TOP, CUP_BOT, W_TOP, W_BOT, R_BOT)
CUP_MASK = Image.new("L", (w, h), 0)
ImageDraw.Draw(CUP_MASK).polygon(P(CUP_PTS), fill=255)

LIQ_TOP = 306.0
LIQ_PTS = cup_profile(CX, LIQ_TOP, CUP_BOT, W_TOP, W_BOT, R_BOT, inset=9)

# ---------------------------------------------------------------- 背景
ys = (np.arange(h) / S)[:, None]
t = ys / (H - 1)
c_top = np.array([255.0, 248.0, 238.0])
c_bot = np.array([255.0, 223.0, 196.0])
bg = c_top[None, None, :] * (1 - t[..., None]) + c_bot[None, None, :] * t[..., None]
bg = np.repeat(bg, w, axis=1) if bg.shape[1] == 1 else bg

yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
glow = np.clip(1 - np.sqrt(((xx - 0.30 * w) / (0.62 * w)) ** 2 +
                           ((yy - 0.16 * h) / (0.52 * h)) ** 2), 0, 1) ** 1.7
bg = bg + glow[..., None] * 20.0
warm = np.clip(1 - np.sqrt(((xx - 0.78 * w) / (0.55 * w)) ** 2 +
                           ((yy - 0.92 * h) / (0.45 * h)) ** 2), 0, 1) ** 2
bg = bg + warm[..., None] * np.array([6.0, -6.0, -14.0])
base = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8)).convert("RGBA")

# 桌面投影（远影 + 接触影）
sh_far = Image.new("RGBA", (w, h), (0, 0, 0, 0))
ImageDraw.Draw(sh_far).ellipse(box(CX - 182, 678, CX + 176, 758), fill=(122, 74, 44, 78))
sh_far = sh_far.filter(ImageFilter.GaussianBlur(22 * S))
sh_near = Image.new("RGBA", (w, h), (0, 0, 0, 0))
ImageDraw.Draw(sh_near).ellipse(box(CX - 124, 684, CX + 124, 722), fill=(92, 52, 28, 130))
sh_near = sh_near.filter(ImageFilter.GaussianBlur(8 * S))
base = Image.alpha_composite(base, sh_far)
base = Image.alpha_composite(base, sh_near)

# ---------------------------------------------------------------- 液体
liq_mask = Image.new("L", (w, h), 0)
d = ImageDraw.Draw(liq_mask)
d.polygon(P(LIQ_PTS), fill=255)
liq_halfw = (cup_width(LIQ_TOP) - 18) / 2
d.ellipse(box(CX - liq_halfw, LIQ_TOP - 15, CX + liq_halfw, LIQ_TOP + 15), fill=255)

stops_y = np.array([LIQ_TOP, 352.0, 410.0, 480.0, 560.0, 630.0, CUP_BOT])
stops_c = np.array([
    [247, 229, 201],
    [239, 211, 173],
    [226, 186, 140],
    [203, 153, 104],
    [178, 123, 76],
    [157, 100, 59],
    [139, 84, 48],
], dtype=np.float64)
ysf = np.arange(h) / S
arr = np.zeros((h, w, 3), np.float64)
for ch in range(3):
    col = np.interp(ysf, stops_y, stops_c[:, ch])
    arr[:, :, ch] = col[:, None]
liq = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).convert("RGBA")
base = overlay(base, liq, liq_mask)

# 液体两侧暗边（立体感）
xs = (np.arange(w) / S)[None, :]
rel = np.abs(xs - CX) / (W_TOP / 2.0)
edge = np.clip((rel - 0.52) / 0.48, 0, 1)[0] ** 1.6 * 78.0
alpha = np.tile(edge[None, :], (h, 1))
side = np.zeros((h, w, 4), np.uint8)
side[..., 0] = 96; side[..., 1] = 58; side[..., 2] = 34
side[..., 3] = alpha.astype(np.uint8)
side = Image.fromarray(side).filter(ImageFilter.GaussianBlur(4 * S))
base = overlay(base, side, liq_mask)

# ---------------------------------------------------------------- 珍珠
pearls = Image.new("RGBA", (w, h), (0, 0, 0, 0))
pd = ImageDraw.Draw(pearls)
placed = []
tries = 0
while len(placed) < 52 and tries < 8000:
    tries += 1
    py_ = random.uniform(575, 690)
    half = (cup_width(py_) - 30) / 2
    px_ = CX + random.uniform(-half, half)
    r = random.uniform(9.0, 13.5)
    if py_ + r > 696:
        continue
    if all(math.hypot(px_ - qx, py_ - qy) > (r + qr) * 0.93 for qx, qy, qr in placed):
        placed.append((px_, py_, r))
        tone = random.choice([(56, 33, 23), (72, 44, 30), (44, 25, 17), (88, 55, 36)])
        pd.ellipse(box(px_ - r, py_ - r, px_ + r, py_ + r), fill=tone + (255,))
        hr = r * 0.33
        hx, hy = px_ - r * 0.40, py_ - r * 0.42
        pd.ellipse(box(hx - hr, hy - hr, hx + hr, hy + hr), fill=(255, 236, 216, 160))
        pd.arc(box(px_ - r * 0.9, py_ - r * 0.9, px_ + r * 0.9, py_ + r * 0.9),
               200, 300, fill=(255, 220, 190, 40), width=int(1.6 * S))
base = overlay(base, pearls, liq_mask)

# 气泡
bub = Image.new("RGBA", (w, h), (0, 0, 0, 0))
bd = ImageDraw.Draw(bub)
for _ in range(46):
    by = random.uniform(370, 585)
    half = (cup_width(by) - 40) / 2
    bx = CX + random.uniform(-half, half)
    br = random.uniform(1.6, 4.6)
    bd.ellipse(box(bx - br, by - br, bx + br, by + br), fill=(255, 249, 240, random.randint(45, 105)))
base = overlay(base, bub.filter(ImageFilter.GaussianBlur(0.8 * S)), liq_mask)

# 液面反光 + 奶泡边缘
surf = Image.new("RGBA", (w, h), (0, 0, 0, 0))
sd = ImageDraw.Draw(surf)
sd.ellipse(box(CX - liq_halfw, LIQ_TOP - 12, CX + liq_halfw, LIQ_TOP + 12),
           fill=(255, 251, 242, 105))
sd.ellipse(box(CX - liq_halfw + 14, LIQ_TOP - 9, CX + liq_halfw - 70, LIQ_TOP + 5),
           fill=(255, 255, 252, 120))
base = overlay(base, surf.filter(ImageFilter.GaussianBlur(2 * S)), liq_mask)

# ---------------------------------------------------------------- 杯身玻璃质感（弧形渐变高光）
gx = xx / S
gy = yy / S
lfade = np.clip((gy - 296) / 60.0, 0, 1) * np.clip((CUP_BOT - 30 - gy) / 95.0, 0, 1)
band1 = np.exp(-(((gx - (CX - 110 - (gy - 306) * 0.09)) / 22.0) ** 2))
band2 = np.exp(-(((gx - (CX + 100 - (gy - 306) * 0.10)) / 8.5) ** 2))
band3 = np.exp(-(((gx - (CX - 76 - (gy - 306) * 0.07)) / 5.0) ** 2))
ha = (band1 * lfade * 62 + band2 * lfade * 58 + band3 * lfade * 34)
hl = np.zeros((h, w, 4), np.uint8)
hl[..., :3] = 255
hl[..., 3] = np.clip(ha, 0, 150).astype(np.uint8)
glass = Image.fromarray(hl).filter(ImageFilter.GaussianBlur(3.5 * S))
base = overlay(base, glass, CUP_MASK)

# 底部茶汤更浓
deep = Image.new("RGBA", (w, h), (0, 0, 0, 0))
ddp = ImageDraw.Draw(deep)
ddp.rectangle([0, int(520 * S), w, int(720 * S)], fill=(126, 74, 40, 46))
deep = deep.filter(ImageFilter.GaussianBlur(10 * S))
base = overlay(base, deep, liq_mask)

# 杯底弧面反光
glowb = Image.new("RGBA", (w, h), (0, 0, 0, 0))
ImageDraw.Draw(glowb).ellipse(box(CX - 92, 658, CX + 92, 706), fill=(255, 246, 236, 42))
base = overlay(base, glowb.filter(ImageFilter.GaussianBlur(7 * S)), CUP_MASK)

# 杯身描边
rim = Image.new("RGBA", (w, h), (0, 0, 0, 0))
rd = ImageDraw.Draw(rim)
rd.line(P(CUP_PTS + [CUP_PTS[0]]), fill=(255, 255, 255, 150), width=int(3.2 * S), joint="curve")
rd.line(P(CUP_PTS + [CUP_PTS[0]]), fill=(198, 150, 112, 90), width=int(1.2 * S), joint="curve")
rim = rim.filter(ImageFilter.GaussianBlur(0.6 * S))
base = Image.alpha_composite(base, rim)

# 杯壁冷凝水珠
dew = Image.new("RGBA", (w, h), (0, 0, 0, 0))
dw = ImageDraw.Draw(dew)
for _ in range(26):
    dy = random.uniform(280, 640)
    side = random.choice([-1, 1])
    off = random.uniform(0.55, 0.92)
    dx = CX + side * off * (cup_width(dy) / 2 - 14)
    r = random.uniform(1.8, 4.2)
    dw.ellipse(box(dx - r, dy - r * 1.15, dx + r, dy + r * 1.15), fill=(255, 252, 246, 70))
    dw.ellipse(box(dx - r * 0.55, dy - r * 0.75, dx - r * 0.05, dy - r * 0.2),
               fill=(255, 255, 255, 130))
base = overlay(base, dew.filter(ImageFilter.GaussianBlur(0.5 * S)), CUP_MASK)

# 杯盖厚度阴影
lip = Image.new("RGBA", (w, h), (0, 0, 0, 0))
ImageDraw.Draw(lip).rectangle([int((CX - 174) * S), int(262 * S), int((CX + 174) * S), int(272 * S)],
                              fill=(150, 128, 112, 60))
base = Image.alpha_composite(base, lip.filter(ImageFilter.GaussianBlur(4 * S)))

# ---------------------------------------------------------------- 吸管
P1 = (306.0, 612.0)          # 管内端
P2 = (404.0, 128.0)          # 管顶端
HALF = 23.0


def tube_mask(p1, p2, half):
    m = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(m)
    dx, dy = p2[0] - p1[0], p2[1] - p1[1]
    L = math.hypot(dx, dy)
    nx, ny = -dy / L, dx / L
    md.polygon(P([(p1[0] + nx * half, p1[1] + ny * half),
                  (p2[0] + nx * half, p2[1] + ny * half),
                  (p2[0] - nx * half, p2[1] - ny * half),
                  (p1[0] - nx * half, p1[1] - ny * half)]), fill=255)
    for c in (p1, p2):
        md.ellipse(box(c[0] - half, c[1] - half, c[0] + half, c[1] + half), fill=255)
    return m


TM = tube_mask(P1, P2, HALF)
tube = Image.new("RGBA", (w, h), (0, 0, 0, 0))
td = ImageDraw.Draw(tube)
td.polygon(P([(0, 0), (W, 0), (W, H), (0, H)]), fill=(247, 197, 209, 255))

dxs, dys = P2[0] - P1[0], P2[1] - P1[1]
L = math.hypot(dxs, dys)
ux, uy = dxs / L, dys / L
vx, vy = -uy, ux


def on_tube(s, v):
    return (P1[0] + ux * s + vx * v, P1[1] + uy * s + vy * v)


s = -40.0
while s < L + 40:
    q = [on_tube(s, -HALF - 4), on_tube(s, HALF + 4),
         on_tube(s + 13, HALF + 4), on_tube(s + 13, -HALF - 4)]
    td.polygon(P(q), fill=(255, 255, 255, 130))
    s += 30
# 明暗立体
shade = Image.new("RGBA", (w, h), (0, 0, 0, 0))
shd = ImageDraw.Draw(shade)
shd.polygon(P([on_tube(0, HALF * 0.15), on_tube(L, HALF * 0.15),
               on_tube(L, HALF + 4), on_tube(0, HALF + 4)]), fill=(198, 132, 152, 130))
shd.polygon(P([on_tube(0, -HALF - 4), on_tube(L, -HALF - 4),
               on_tube(L, -HALF * 0.35), on_tube(0, -HALF * 0.35)]), fill=(255, 255, 255, 150))
tube = Image.alpha_composite(tube, shade.filter(ImageFilter.GaussianBlur(2 * S)))
tube.putalpha(TM)

# 吸管投影落在液体上
tproj = tube.copy()
tproj.putalpha(ImageChops.multiply(tube.getchannel("A"),
                                   Image.new("L", (w, h), 55)))
tproj = tproj.filter(ImageFilter.GaussianBlur(6 * S))
tproj = Image.merge("RGBA", (Image.new("L", (w, h), 60),
                             Image.new("L", (w, h), 32),
                             Image.new("L", (w, h), 20),
                             tproj.getchannel("A")))
tproj = ImageChops.offset(tproj, int(9 * S), int(7 * S))
base = overlay(base, tproj, CUP_MASK)
base = Image.alpha_composite(base, tube)

# ---------------------------------------------------------------- 杯盖
LID_TOP, LID_BOT = 214.0, 268.0
LID_HW = 176.0
lid = Image.new("RGBA", (w, h), (0, 0, 0, 0))
ld = ImageDraw.Draw(lid)
ld.rounded_rectangle(box(CX - LID_HW, LID_TOP, CX + LID_HW, LID_BOT),
                     radius=22 * S, fill=(240, 242, 246, 255))
ld.rounded_rectangle(box(CX - LID_HW, LID_TOP, CX + LID_HW, LID_BOT),
                     radius=22 * S, outline=(206, 210, 218, 255), width=int(2.2 * S))
ld.rounded_rectangle(box(CX - LID_HW + 4, LID_TOP + 4, CX + LID_HW - 4, LID_TOP + 20),
                     radius=9 * S, fill=(255, 255, 255, 190))
ld.rounded_rectangle(box(CX - LID_HW + 6, LID_BOT - 22, CX + LID_HW - 6, LID_BOT - 6),
                     radius=8 * S, fill=(214, 218, 226, 150))
ld.rounded_rectangle(box(CX - 158, 250, CX + 158, 262), radius=6 * S, fill=(196, 201, 210, 120))
base = Image.alpha_composite(base, lid)

# 盖子之上的吸管段
above = tube.copy()
cut = Image.new("L", (w, h), 0)
ImageDraw.Draw(cut).rectangle([0, 0, w, int(LID_TOP * S)], fill=255)
above.putalpha(ImageChops.multiply(tube.getchannel("A"), cut))
base = Image.alpha_composite(base, above)

# ---------------------------------------------------------------- 光效/收尾
# 柔光
soft = base.filter(ImageFilter.GaussianBlur(18 * S))
base = Image.blend(base, soft, 0.07)
# 暗角
vg = np.zeros((h, w, 4), np.uint8)
dd = np.sqrt(((xx - w / 2) / (0.72 * w)) ** 2 + ((yy - h / 2) / (0.72 * h)) ** 2)
vg[..., 3] = (np.clip((dd - 0.62) / 0.55, 0, 1) ** 1.5 * 62).astype(np.uint8)
vg[..., 0] = 92; vg[..., 1] = 52; vg[..., 2] = 30
base = Image.alpha_composite(base, Image.fromarray(vg))

img = base.convert("RGB").resize((W, H), Image.LANCZOS)
img.save("milk_tea.png", "PNG")
print("saved", img.size)
