# -*- coding: utf-8 -*-
"""二次元角色：Q 版少女「雷小池」——安全卫士拟人化（v4 重画）

画法：先把头发/身体/脸的轮廓各当作「一整条闭合折线」画出来，
再叠高光与阴影。避免上一版用一堆椭圆+多边形拼接时留下的硬接缝
（那一版看起来像戴了蓝色头盔）。

结构分层（从下到上）：
  1. 后发轮廓（含双马尾）
  2. 身体（水手服 + 领结 + 手臂）
  3. 脸（蛋形 + 脸颊阴影 + 脖子投影）
  4. 前发轮廓（顶发 + 鬓发 + 刘海），露出额头与眼睛
  5. 高光 / 刘海投影 / 腮红
  6. 五官（眼、眉、鼻、嘴）
"""
from PIL import Image, ImageDraw, ImageFilter
import math
from theme import *

SKIN     = (255, 236, 228)
SKIN_S   = (248, 206, 198)
SKIN_D   = (235, 176, 172)
HAIR     = (158, 196, 255)
HAIR_S   = (120, 162, 242)
HAIR_D   = (88, 128, 216)
HAIR_H   = (228, 244, 255)
EYE_B    = (82, 136, 240)
EYE_D    = (38, 62, 152)
EYE_L    = (170, 210, 255)
BLUSH    = (255, 150, 176)
LINE     = (64, 80, 136)
CLOTH    = (252, 253, 255)
CLOTH_S  = (221, 230, 248)
SAILOR   = (94, 138, 238)
SAILOR_S = (66, 104, 200)
RIBBON   = (255, 120, 162)
RIBBON_S = (224, 84, 130)
WHITE    = (255, 255, 255)

CW, CH = 820, 980
CX, CY = 410, 360
R  = 132          # 头宽半径
RY = 138          # 头高半径


def _rot(pts, a, cx, cy):
    ca, sa = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * ca - (y - cy) * sa,
             cy + (x - cx) * sa + (y - cy) * ca) for x, y in pts]


def _smooth(pts, k=1):
    """Chaikin 一次细分，把折线磨圆"""
    for _ in range(k):
        out = []
        n = len(pts)
        for i in range(n):
            x0, y0 = pts[i]
            x1, y1 = pts[(i + 1) % n]
            out.append((0.75 * x0 + 0.25 * x1, 0.75 * y0 + 0.25 * y1))
            out.append((0.25 * x0 + 0.75 * x1, 0.25 * y0 + 0.75 * y1))
        pts = out
    return pts


def _hair_back(hx, hy, t):
    """后发 + 双马尾 的闭合轮廓（局部坐标）"""
    r, ry = R, RY
    pts = [
        (hx - r * 0.98, hy - ry * 0.70),
        (hx - r * 0.96, hy + ry * 0.30),
        (hx - r * 0.86, hy + ry * 1.02),
        (hx - r * 0.60, hy + ry * 1.46),
        (hx - r * 0.18, hy + ry * 1.60),
        (hx + r * 0.18, hy + ry * 1.60),
        (hx + r * 0.60, hy + ry * 1.46),
        (hx + r * 0.86, hy + ry * 1.02),
        (hx + r * 0.96, hy + ry * 0.30),
        (hx + r * 0.98, hy - ry * 0.70),
        (hx + r * 0.62, hy - ry * 1.06),
        (hx, hy - ry * 1.16),
        (hx - r * 0.62, hy - ry * 1.06),
    ]
    return _smooth(pts, 2)


def _twintail(hx, hy, t, sx):
    r, ry = R, RY
    sw = math.sin(t * 1.7 + (0 if sx < 0 else 1.3)) * 8
    x0 = hx + sx * r * 0.98
    pts = [
        (x0, hy - ry * 0.30),
        (x0 + sx * r * 0.30 + sw * 0.6, hy + ry * 0.16),
        (x0 + sx * r * 0.42 + sw, hy + ry * 0.68),
        (x0 + sx * r * 0.30 + sw * 1.4, hy + ry * 1.22),
        (x0 + sx * r * 0.08 + sw * 1.5, hy + ry * 1.42),
        (x0 - sx * r * 0.04 + sw * 1.2, hy + ry * 1.10),
        (x0 - sx * r * 0.06 + sw * 0.5, hy + ry * 0.36),
        (x0 - sx * r * 0.06, hy - ry * 0.06),
    ]
    return _smooth(pts, 2)


def _face_path(hx, hy):
    r, ry = R, RY
    pts = [
        (hx - r * 1.00, hy - ry * 0.22),
        (hx - r * 0.98, hy + ry * 0.30),
        (hx - r * 0.66, hy + ry * 0.82),
        (hx - r * 0.32, hy + ry * 1.14),
        (hx, hy + ry * 1.26),
        (hx + r * 0.32, hy + ry * 1.14),
        (hx + r * 0.66, hy + ry * 0.82),
        (hx + r * 0.98, hy + ry * 0.30),
        (hx + r * 1.00, hy - ry * 0.22),
        (hx + r * 0.82, hy - ry * 0.90),
        (hx, hy - ry * 1.12),
        (hx - r * 0.82, hy - ry * 0.90),
    ]
    return _smooth(pts, 2)


def _bangs(hx, hy, t):
    """前发：一整条轮廓，顶发 + 鬓发 + 刘海尖"""
    r, ry = R, RY
    sway = math.sin(t * 1.9) * 3
    pts = [
        # 左侧鬓发起
        (hx - r * 1.00, hy + ry * 0.62),
        (hx - r * 1.04, hy - ry * 0.34),
        (hx - r * 0.94, hy - ry * 0.96),
        (hx - r * 0.52, hy - ry * 1.22),
        (hx - r * 0.02, hy - ry * 1.30),
        (hx + r * 0.52, hy - ry * 1.22),
        (hx + r * 0.94, hy - ry * 0.96),
        (hx + r * 1.04, hy - ry * 0.34),
        (hx + r * 1.00, hy + ry * 0.62),
        # 刘海下缘（右→左），逐束尖角
        (hx + r * 0.82, hy - ry * 0.02),
        (hx + r * 0.56, hy - ry * 0.14),
        (hx + r * 0.44, hy - ry * 0.10),
        (hx + r * 0.26, hy - ry * 0.20),
        (hx + r * 0.06 + sway * 0.3, hy - ry * 0.20),
        (hx - r * 0.14, hy - ry * 0.28),
        (hx - r * 0.32, hy - ry * 0.12),
        (hx - r * 0.52, hy - ry * 0.14),
        (hx - r * 0.78, hy - ry * 0.02),
        (hx - r * 0.96, hy + ry * 0.10),
    ]
    return _smooth(pts, 1)


def _render(t=0.0, blink=False, look=0.0, mouth=0.0, wave=0.0):
    img = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    bob = math.sin(t * 1.9) * 4.5
    hx, hy = CX, CY + bob
    r, ry = R, RY

    # ---------- 1. 后发 ----------
    d.polygon(_hair_back(hx, hy, t), fill=HAIR_S + (255,))
    for sx in (-1, 1):
        d.polygon(_twintail(hx, hy, t, sx), fill=HAIR + (255,))
    # 后发底部深色
    d.polygon(_smooth([(hx - r * 0.60, hy + ry * 1.10), (hx - r * 0.16, hy + ry * 1.44),
                       (hx + r * 0.16, hy + ry * 1.44), (hx + r * 0.60, hy + ry * 1.10),
                       (hx + r * 0.16, hy + ry * 1.60), (hx - r * 0.16, hy + ry * 1.60)], 2),
              fill=HAIR_D + (170,))

    # ---------- 2. 身体 ----------
    by = hy + ry * 1.02
    d.rounded_rectangle([hx - r * 0.19, by - ry * 0.22, hx + r * 0.19, by + ry * 0.16],
                        radius=int(r * 0.10), fill=SKIN_S + (255,))
    torso = _smooth([(hx - r * 0.60, by + ry * 0.08), (hx + r * 0.60, by + ry * 0.08),
                     (hx + r * 0.94, by + ry * 1.30), (hx + r * 0.90, by + ry * 1.56),
                     (hx - r * 0.90, by + ry * 1.56), (hx - r * 0.94, by + ry * 1.30)], 2)
    d.polygon(torso, fill=CLOTH + (255,))
    # 右半身阴影
    d.polygon([(hx + r * 0.30, by + ry * 0.08), (hx + r * 0.60, by + ry * 0.08),
               (hx + r * 0.94, by + ry * 1.30), (hx + r * 0.90, by + ry * 1.56),
               (hx + r * 0.52, by + ry * 1.56), (hx + r * 0.46, by + ry * 1.20)],
              fill=CLOTH_S + (120,))
    # 水手领
    collar = _smooth([(hx - r * 0.62, by + ry * 0.04), (hx + r * 0.62, by + ry * 0.04),
                      (hx + r * 0.46, by + ry * 0.50), (hx, by + ry * 0.30),
                      (hx - r * 0.46, by + ry * 0.50)], 2)
    d.polygon(collar, fill=SAILOR + (255,))
    # 前胸 V 字
    d.polygon([(hx - r * 0.26, by + ry * 0.54), (hx + r * 0.26, by + ry * 0.54),
               (hx + r * 0.22, by + ry * 0.92), (hx, by + ry * 1.10),
               (hx - r * 0.22, by + ry * 0.92)], fill=CLOTH + (255,))
    d.line([(hx - r * 0.26, by + ry * 0.54), (hx, by + ry * 1.10),
            (hx + r * 0.26, by + ry * 0.54)], fill=SAILOR_S + (255,), width=7)
    # 领结
    d.polygon([(hx - 6, by + ry * 0.90), (hx - r * 0.30, by + ry * 0.70),
               (hx - r * 0.30, by + ry * 1.18)], fill=RIBBON + (255,))
    d.polygon([(hx + 6, by + ry * 0.90), (hx + r * 0.30, by + ry * 0.70),
               (hx + r * 0.30, by + ry * 1.18)], fill=RIBBON + (255,))
    d.ellipse([hx - r * 0.10, by + ry * 0.80, hx + r * 0.10, by + ry * 1.04],
              fill=(255, 176, 198, 255))
    # 左臂
    lx = hx - r * 0.74
    d.line([(lx, by + ry * 0.30), (lx - r * 0.04, by + ry * 1.00)],
           fill=CLOTH + (255,), width=int(r * 0.30))
    d.ellipse([lx - r * 0.22, by + ry * 0.96, lx + r * 0.14, by + ry * 1.34],
              fill=SKIN + (255,))
    # 右臂（挥动）
    wa = wave * math.sin(t * 6.5) * 0.7
    rx0, ry0 = hx + r * 0.74, by + ry * 0.30
    rx1 = hx + r * 0.82 + math.sin(wa) * r * 0.68
    ry1 = by - ry * 0.28 - math.cos(wa) * r * 0.26
    d.line([(rx0, ry0), (rx1, ry1)], fill=CLOTH + (255,), width=int(r * 0.30))
    d.ellipse([rx1 - r * 0.19, ry1 - r * 0.19, rx1 + r * 0.19, ry1 + r * 0.19],
              fill=SKIN + (255,))

    # ---------- 3. 脸 ----------
    d.polygon(_face_path(hx, hy), fill=SKIN + (255,))
    # 脸颊阴影
    for sx in (-1, 1):
        sh = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
        ImageDraw.Draw(sh).ellipse(
            [hx + sx * r * 0.58 - r * 0.34, hy - ry * 0.10,
             hx + sx * r * 0.58 + r * 0.34, hy + ry * 0.80], fill=SKIN_S + (85,))
        img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(16)))
        d = ImageDraw.Draw(img)
    d.ellipse([hx - r * 0.32, hy + ry * 0.84, hx + r * 0.32, hy + ry * 1.20],
              fill=SKIN_D + (120,))

    # ---------- 4. 前发 ----------
    fh = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    ImageDraw.Draw(fh).polygon(_bangs(hx, hy, t), fill=HAIR + (255,))
    img.alpha_composite(fh)
    d = ImageDraw.Draw(img)
    # 呆毛
    ah = math.sin(t * 2.6) * 10
    d.polygon([(hx - r * 0.05, hy - ry * 1.12), (hx + r * 0.12, hy - ry * 1.04),
               (hx + r * 0.40 + ah, hy - ry * 1.58), (hx + r * 0.08 + ah * 0.5, hy - ry * 1.28)],
              fill=HAIR + (255,))

    # ---------- 5. 高光 / 阴影 / 腮红 ----------
    hl = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.arc([hx - r * 0.90, hy - ry * 1.34, hx - r * 0.02, hy - ry * 0.62],
           start=196, end=324, fill=HAIR_H + (225,), width=16)
    hd.arc([hx - r * 0.04, hy - ry * 1.30, hx + r * 0.86, hy - ry * 0.66],
           start=214, end=336, fill=HAIR_H + (150,), width=13)
    img.alpha_composite(hl.filter(ImageFilter.GaussianBlur(4)))
    # 刘海在额头的投影
    sh = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([hx - r * 0.82, hy - ry * 0.66, hx + r * 0.82, hy + ry * 0.06],
                               fill=(150, 176, 238, 80))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(16)))
    d = ImageDraw.Draw(img)
    for sx in (-1, 1):
        bx = hx + sx * r * 0.58
        byy = hy + ry * 0.48
        bh = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
        ImageDraw.Draw(bh).ellipse([bx - r * 0.21, byy - r * 0.12,
                                    bx + r * 0.21, byy + r * 0.12], fill=BLUSH + (115,))
        img.alpha_composite(bh.filter(ImageFilter.GaussianBlur(8)))
        d = ImageDraw.Draw(img)
        for k in (-1, 0, 1):
            d.line([(bx + k * r * 0.10 - r * 0.02, byy - r * 0.05),
                    (bx + k * r * 0.10 + r * 0.02, byy + r * 0.05)],
                   fill=(255, 128, 156, 150), width=4)

    # ---------- 6. 五官 ----------
    ey = hy + ry * 0.22
    for sx in (-1, 1):
        ex = hx + sx * r * 0.40
        d.ellipse([ex - r * 0.27, ey - r * 0.26, ex + r * 0.27, ey + r * 0.28],
                  fill=WHITE + (255,))
        if blink:
            d.ellipse([ex - r * 0.31, ey - r * 0.30, ex + r * 0.31, ey + r * 0.32],
                      fill=SKIN + (255,))
            d.arc([ex - r * 0.26, ey - r * 0.30, ex + r * 0.26, ey + r * 0.20],
                  start=16, end=164, fill=LINE + (255,), width=10)
            continue
        ic = (ex + look * r * 0.09, ey + r * 0.04)
        d.ellipse([ic[0] - r * 0.20, ic[1] - r * 0.24, ic[0] + r * 0.20, ic[1] + r * 0.24],
                  fill=EYE_L + (255,))
        d.ellipse([ic[0] - r * 0.20, ic[1] - r * 0.24, ic[0] + r * 0.20, ic[1] + r * 0.03],
                  fill=EYE_B + (255,))
        d.ellipse([ic[0] - r * 0.18, ic[1] - r * 0.04, ic[0] + r * 0.18, ic[1] + r * 0.24],
                  fill=EYE_D + (255,))
        d.ellipse([ic[0] - r * 0.09, ic[1] - r * 0.11, ic[0] + r * 0.09, ic[1] + r * 0.12],
                  fill=(24, 34, 78, 255))
        d.ellipse([ic[0] - r * 0.15, ic[1] - r * 0.24, ic[0] - r * 0.02, ic[1] - r * 0.08],
                  fill=WHITE + (255,))
        d.ellipse([ic[0] + r * 0.04, ic[1] + r * 0.08, ic[0] + r * 0.13, ic[1] + r * 0.18],
                  fill=WHITE + (200,))
        d.arc([ex - r * 0.30, ey - r * 0.36, ex + r * 0.30, ey + r * 0.26],
              start=190, end=350, fill=LINE + (255,), width=11)
        d.line([ex + sx * r * 0.25, ey - r * 0.27, ex + sx * r * 0.38, ey - r * 0.40],
               fill=LINE + (255,), width=8)
        d.arc([ex - r * 0.28, ey - r * 0.14, ex + r * 0.28, ey + r * 0.40],
              start=12, end=168, fill=(120, 140, 200, 170), width=5)
    # 眉毛
    for sx in (-1, 1):
        bx = hx + sx * r * 0.46
        d.arc([bx - r * 0.24, ey - r * 0.82, bx + r * 0.24, ey - r * 0.40],
              start=200, end=340, fill=HAIR_D + (255,), width=8)
    # 鼻子
    d.ellipse([hx - r * 0.032, hy + ry * 0.58, hx + r * 0.032, hy + ry * 0.64],
              fill=SKIN_D + (200,))
    # 嘴
    mx, my = hx, hy + ry * 0.80
    if mouth < 0.15:
        d.arc([mx - r * 0.16, my - r * 0.12, mx + r * 0.16, my + r * 0.16],
              start=14, end=166, fill=(206, 84, 106, 255), width=7)
    else:
        ow = r * (0.12 + 0.09 * mouth)
        oh = r * (0.09 + 0.19 * mouth)
        d.ellipse([mx - ow, my - oh * 0.5, mx + ow, my + oh], fill=(190, 62, 88, 255))
        d.ellipse([mx - ow * 0.55, my - oh * 0.2, mx + ow * 0.55, my + oh * 0.75],
                  fill=(255, 148, 170, 255))

    return img


def draw_character(base, cx, cy, scale=1.0, t=0.0, blink=False, look=0.0, mouth=0.0, wave=0.0):
    """cx,cy 为头部中心（目标画布坐标）"""
    local = _render(t, blink, look, mouth, wave)
    if scale != 1.0:
        nw, nh = int(CW * scale), int(CH * scale)
        local = local.resize((nw, nh), Image.LANCZOS)
        ox = int(cx - CX * scale)
        oy = int(cy - CY * scale)
    else:
        ox = int(cx - CX)
        oy = int(cy - CY)
    base.alpha_composite(local, (ox, oy))
    return base
