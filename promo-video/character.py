# -*- coding: utf-8 -*-
"""二次元角色：Q版少女「雷小池」——安全卫士拟人化
   在局部画布上绘制后整体贴入，避免坐标漂移。"""
from PIL import Image, ImageDraw, ImageFilter
import math
from theme import *

SKIN   = (255, 233, 222)
SKIN_S = (246, 208, 196)
HAIR   = (152, 192, 255)
HAIR_S = (120, 162, 238)
HAIR_D = (96, 138, 220)
HAIR_H = (225, 240, 255)
EYE_B  = (66, 118, 226)
EYE_D  = (34, 58, 145)
EYE_L  = (150, 196, 255)
BLUSH  = (255, 158, 182)
LINE   = (58, 74, 128)

# 局部画布：头中心位于 (CX, CY)，画布足够大
CW, CH = 760, 900
CX, CY = 380, 340
R = 118  # 头半径（局部坐标）


def _render(t=0.0, blink=False, look=0.0, mouth=0.0, wave=0.0):
    img = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    bob = math.sin(t * 2.0) * 4
    hx, hy = CX, CY + bob
    r = R

    # ---------- 后发 ----------
    d.ellipse([hx-140, hy-34, hx+140, hy+236], fill=HAIR_S + (255,))
    for sx in (-1, 1):
        d.ellipse([hx+sx*r*0.60-r*0.30, hy-r*0.45,
                   hx+sx*r*0.60+r*0.30, hy+r*1.05], fill=HAIR+(255,))
    d.ellipse([hx-124, hy-10, hx+124, hy+230], fill=HAIR_S + (255,))
    for sx in (-1, 1):
        d.ellipse([hx+sx*r*0.66-r*0.26, hy-r*0.35,
                   hx+sx*r*0.66+r*0.26, hy+r*0.98], fill=HAIR+(255,))

    # ---------- 身体 ----------
    by = hy + r * 0.95
    d.ellipse([hx-152, by+70, hx+152, by+430], fill=(250, 252, 255, 255))
    d.polygon([(hx-134, by+110), (hx+134, by+110), (hx+172, by+440), (hx-172, by+440)],
              fill=(250, 252, 255, 255))
    # 水手领
    d.polygon([(hx-94, by+4), (hx+94, by+4), (hx+60, by+72), (hx, by+112), (hx-60, by+72)],
              fill=(96, 136, 232, 255))
    d.polygon([(hx-40, by+26), (hx+40, by+26), (hx, by+96)], fill=(255, 255, 255, 255))
    # 领结
    d.polygon([(hx-4, by+94), (hx-42, by+78), (hx-42, by+120)], fill=(255, 126, 166, 255))
    d.polygon([(hx+4, by+94), (hx+42, by+78), (hx+42, by+120)], fill=(255, 126, 166, 255))
    d.ellipse([hx-13, by+82, hx+13, by+108], fill=(255, 176, 200, 255))
    # 左臂
    d.rounded_rectangle([hx-158, by+80, hx-112, by+300], radius=22,
                        fill=(96, 136, 232, 255))
    d.ellipse([hx-162, by+282, hx-108, by+336], fill=SKIN + (255,))
    # 右臂（挥动）
    wa = wave * math.sin(t * 7) * 0.85
    rx0, ry0 = hx + 110, by + 78
    rx1 = hx + 118 + math.sin(wa) * 80
    ry1 = by - 40 - math.cos(wa) * 30
    d.line([(rx0, ry0), (rx1, ry1)], fill=(96, 136, 232, 255), width=34)
    d.ellipse([rx1-22, ry1-22, rx1+22, ry1+22], fill=SKIN + (255,))

    # ---------- 脸 ----------
    d.ellipse([hx-r, hy-r*1.0, hx+r, hy+r*0.98], fill=SKIN + (255,))
    d.ellipse([hx-r*0.76, hy+r*0.06, hx+r*0.76, hy+r*1.16], fill=SKIN + (255,))

    # ---------- 刘海 ----------
    bl = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bl)
    # 主刘海：覆盖额头，上缘到头顶上方
    bd.ellipse([hx-r*1.02, hy-r*1.16, hx+r*1.02, hy+r*0.26], fill=HAIR + (255,))
    # 分缕
    for k in (-3, -2, 2, 3):
        x = hx + k*r*0.30
        tipx = x + k*r*0.04
        tipy = hy + r*0.20 + math.sin(t*1.6+k)*3 + abs(k)*r*0.02
        bd.polygon([(x-r*0.20, hy-r*0.30), (x+r*0.20, hy-r*0.30), (tipx, tipy)],
                   fill=HAIR + (255,))
    # 呆毛
    ah = math.sin(t*2.6)*9
    bd.polygon([(hx-6, hy-r*1.08), (hx+14, hy-r*1.00), (hx+44+ah, hy-r*1.60)],
               fill=HAIR + (255,))
    img.alpha_composite(bl)
    d = ImageDraw.Draw(img)

    # 刘海投影
    sh = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([hx-r*0.82, hy-r*0.86, hx+r*0.82, hy+r*0.06],
                               fill=(120, 150, 230, 65))
    sh = sh.filter(ImageFilter.GaussianBlur(12))
    img.alpha_composite(sh)

    # 发丝高光
    hl = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.arc([hx-r*0.86, hy-r*0.92, hx+r*0.02, hy-r*0.26], start=200, end=340,
           fill=HAIR_H + (210,), width=13)
    hd.arc([hx-r*0.02, hy-r*0.90, hx+r*0.86, hy-r*0.34], start=205, end=345,
           fill=HAIR_H + (150,), width=11)
    hl = hl.filter(ImageFilter.GaussianBlur(3))
    img.alpha_composite(hl)
    d = ImageDraw.Draw(img)

    # ---------- 腮红 ----------
    for sx in (-1, 1):
        d.ellipse([hx+sx*r*0.76-r*0.18, hy+r*0.34, hx+sx*r*0.76+r*0.18, hy+r*0.55],
                  fill=BLUSH + (115,))

    # ---------- 眼睛 ----------
    ey = hy + r*0.10
    for sx in (-1, 1):
        ex = hx + sx*r*0.44
        d.ellipse([ex-r*0.27, ey-r*0.31, ex+r*0.27, ey+r*0.33], fill=WHITE + (255,))
        if blink:
            d.ellipse([ex-r*0.27, ey-r*0.06, ex+r*0.27, ey+r*0.12], fill=SKIN_S + (255,))
            d.arc([ex-r*0.26, ey-r*0.20, ex+r*0.26, ey+r*0.16], start=20, end=160,
                  fill=LINE + (255,), width=7)
            continue
        ic = (ex + look*r*0.11, ey + r*0.01)
        d.ellipse([ic[0]-r*0.21, ic[1]-r*0.25, ic[0]+r*0.21, ic[1]+r*0.27], fill=EYE_B + (255,))
        d.ellipse([ic[0]-r*0.21, ic[1]-r*0.04, ic[0]+r*0.21, ic[1]+r*0.27], fill=EYE_D + (255,))
        d.ellipse([ic[0]-r*0.21, ic[1]-r*0.25, ic[0]+r*0.21, ic[1]-r*0.09], fill=EYE_L + (255,))
        d.ellipse([ic[0]-r*0.09, ic[1]-r*0.09, ic[0]+r*0.09, ic[1]+r*0.13], fill=(18, 28, 66, 255))
        d.ellipse([ic[0]-r*0.12, ic[1]-r*0.20, ic[0]-r*0.01, ic[1]-r*0.07], fill=WHITE + (255,))
        d.ellipse([ic[0]+r*0.05, ic[1]+r*0.08, ic[0]+r*0.12, ic[1]+r*0.15], fill=WHITE + (205,))
        d.arc([ex-r*0.29, ey-r*0.36, ex+r*0.29, ey+r*0.30], start=196, end=344,
              fill=LINE + (255,), width=10)
        d.line([ex+sx*r*0.24, ey-r*0.24, ex+sx*r*0.36, ey-r*0.36], fill=LINE + (255,), width=8)

    for sx in (-1, 1):
        bx = hx + sx*r*0.44
        d.arc([bx-r*0.20, ey-r*0.74, bx+r*0.20, ey-r*0.44], start=205, end=335,
              fill=HAIR_D + (255,), width=7)

    mx, my = hx, hy + r*0.62
    if mouth < 0.15:
        d.arc([mx-r*0.15, my-r*0.10, mx+r*0.15, my+r*0.15], start=15, end=165,
              fill=(214, 92, 112, 255), width=6)
    else:
        ow = r*(0.11+0.10*mouth); oh = r*(0.08+0.20*mouth)
        d.ellipse([mx-ow, my-oh*0.4, mx+ow, my+oh], fill=(198, 68, 92, 255))
        d.ellipse([mx-ow*0.55, my+oh*0.25, mx+ow*0.55, my+oh*0.85], fill=(255, 152, 172, 255))

    return img


def draw_character(base, cx, cy, scale=1.0, t=0.0, blink=False, look=0.0, mouth=0.0, wave=0.0):
    """cx,cy 为头部中心（目标画布坐标）"""
    local = _render(t, blink, look, mouth, wave)
    if scale != 1.0:
        nw, nh = int(CW*scale), int(CH*scale)
        local = local.resize((nw, nh), Image.LANCZOS)
        ox = int(cx - CX*scale)
        oy = int(cy - CY*scale)
    else:
        ox = int(cx - CX)
        oy = int(cy - CY)
    base.alpha_composite(local, (ox, oy))
    return base
