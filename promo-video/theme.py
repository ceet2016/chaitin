# -*- coding: utf-8 -*-
"""视觉主题：二次元配色 + 绘制工具"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math, random

W, H = 1920, 1080
FPS = 30

FONT_PATH = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
# 字体索引：0=正黑, 1=点阵正黑, 2=等宽

def font(size, idx=0):
    return ImageFont.truetype(FONT_PATH, size, index=idx)

# 二次元色板
PINK   = (255, 138, 178)
PINK_L = (255, 197, 219)
SKY    = (120, 190, 255)
SKY_D  = (74, 124, 226)
PURPLE = (168, 140, 255)
MINT   = (120, 230, 210)
GOLD   = (255, 214, 120)
WHITE  = (255, 255, 255)
INK    = (46, 52, 80)
INK_L  = (110, 120, 150)
BLUE_BRAND = (48, 84, 200)   # 雷池主色

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i]-a[i])*t)) for i in range(3))

def vgrad(size, top, bot, stops=None):
    """垂直渐变，可选多段。stops: [(t,color),...]"""
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    if stops:
        for y in range(h):
            t = y/(h-1)
            # 找区间
            for i in range(len(stops)-1):
                t0,c0 = stops[i]; t1,c1 = stops[i+1]
                if t0 <= t <= t1:
                    k = (t-t0)/(t1-t0) if t1>t0 else 0
                    px[0,y] = lerp(c0,c1,k)
                    break
            else:
                px[0,y] = stops[-1][1]
    else:
        for y in range(h):
            px[0,y] = lerp(top, bot, y/(h-1))
    return img.resize((w,h), Image.BILINEAR)

def ease_out(t):
    return 1 - (1-t)**3

def ease_in_out(t):
    return 3*t*t - 2*t*t*t

def smoothstep(t):
    t = max(0.0, min(1.0, t))
    return t*t*(3-2*t)

def clamp01(t):
    return max(0.0, min(1.0, t))

def alpha_composite(base, layer):
    return Image.alpha_composite(base.convert("RGBA"), layer).convert("RGB")

def glow_circle(draw, cx, cy, r, color, alpha=180, blur=0):
    pass

_GLOW_CACHE = {}
def paste_glow(base, cx, cy, r, color, alpha=140):
    """柔和光晕（缓存）"""
    key = (int(r), color, int(alpha))
    if key not in _GLOW_CACHE:
        _GLOW_CACHE[key] = _make_glow(int(r), color, int(alpha))
    gl = _GLOW_CACHE[key]
    base.alpha_composite(gl, (int(cx-r*1.2), int(cy-r*1.2)))

def _make_glow(r, color, alpha):
    d = int(r*2.4)
    gl = Image.new("RGBA", (d, d), (0,0,0,0))
    gd = ImageDraw.Draw(gl)
    steps = 24
    for i in range(steps, 0, -1):
        rr = r * i/steps
        a = int(alpha * (1 - i/steps)**2)
        gd.ellipse([d/2-rr, d/2-rr, d/2+rr, d/2+rr], fill=color+(a,))
    gl = gl.filter(ImageFilter.GaussianBlur(r*0.25))
    return gl

def sparkle(base, cx, cy, r, color=WHITE, alpha=255, rot=0):
    """四芒星闪光"""
    d = int(r*3)
    sp = Image.new("RGBA", (d,d), (0,0,0,0))
    sd = ImageDraw.Draw(sp)
    c = d/2
    pts = []
    for i in range(4):
        ang = rot + i*math.pi/2
        # 长尖
        pts.append((c + math.cos(ang)*r, c + math.sin(ang)*r))
    # 用两个扁菱形构成星
    for ang_off in (0, math.pi/2):
        poly = []
        for i in range(4):
            ang = rot + ang_off + i*math.pi/2
            L = r if i%2==0 else r*0.16
            poly.append((c+math.cos(ang)*L, c+math.sin(ang)*L))
        sd.polygon(poly, fill=color+(alpha,))
    sp = sp.filter(ImageFilter.GaussianBlur(r*0.10))
    base.alpha_composite(sp, (int(cx-c), int(cy-c)))

def rounded_rect(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)

_CARD_CACHE = {}
def _make_card(box, radius, tint, outline, shadow):
    """返回 (图层, 左上角偏移)，图层只覆盖卡片区域"""
    x0,y0,x1,y1 = [int(v) for v in box]
    key = (x0,y0,x1,y1, radius, tint, outline, shadow)
    if key in _CARD_CACHE: return _CARD_CACHE[key]
    pad = 30 if shadow else 2
    w = (x1-x0) + pad*2
    h = (y1-y0) + pad*2
    canvas = Image.new("RGBA", (w, h), (0,0,0,0))
    if shadow:
        sh = Image.new("RGBA", (w,h), (0,0,0,0))
        ImageDraw.Draw(sh).rounded_rectangle([pad+6,pad+10,pad+(x1-x0)+6,pad+(y1-y0)+10],
                                             radius=radius, fill=(40,50,90,205))
        sh = sh.filter(ImageFilter.GaussianBlur(14))
        canvas.alpha_composite(sh)
    ImageDraw.Draw(canvas).rounded_rectangle([pad,pad,pad+(x1-x0),pad+(y1-y0)],
        radius=radius, fill=tint+(255,), outline=outline+(205,), width=2)
    res = (canvas, x0-pad, y0-pad)
    _CARD_CACHE[key] = res
    return res

def glass_card(base, box, radius=28, tint=(255,255,255), alpha=210, shadow=True, outline=(255,255,255)):
    """玻璃卡片：只在该卡局部区域合成，避免整屏大图层"""
    card, ox, oy = _make_card(box, radius, tint, outline, shadow)
    if alpha >= 254:
        base.alpha_composite(card, (ox, oy))
    else:
        c = card.copy()
        c.putalpha(c.getchannel("A").point(lambda v: v*int(alpha)//255))
        base.alpha_composite(c, (ox, oy))
    return base

def text_center(draw, cx, y, s, f, fill, anchor="mm"):
    draw.text((cx, y), s, font=f, fill=fill, anchor=anchor)

def measure(draw, s, f):
    b = draw.textbbox((0,0), s, font=f)
    return b[2]-b[0], b[3]-b[1]

def wrap_cjk(s, f, max_w):
    """中文按宽度折行（逐字）"""
    lines, cur = [], ""
    for ch in s:
        if ch == "\n":
            lines.append(cur); cur=""; continue
        t = cur + ch
        if measure(ImageDraw.Draw(Image.new("RGB",(10,10))), t, f)[0] > max_w and cur:
            lines.append(cur); cur = ch
        else:
            cur = t
    if cur: lines.append(cur)
    return lines
