# -*- coding: utf-8 -*-
"""伪 UI 组件：指标卡、手机框、图表、进度条"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math
from theme import *

def metric_card(base, box, title, value, subtitle, accent=BLUE_BRAND, t=0.0, prog=1.0):
    glass_card(base, box, radius=24, alpha=232, tint=(255,255,255))
    x0,y0,x1,y1 = box
    d = ImageDraw.Draw(base)
    # 竖条
    d.rounded_rectangle([x0+24,y0+26,x0+32,y0+62], radius=4, fill=accent+(255,))
    f_t = font(30); f_v = font(64); f_s = font(24)
    d.text((x0+48,y0+26), title, font=f_t, fill=INK+(255,))
    # 数字（缓动增长感，这里直接显示）
    d.text((x0+30,y0+84), value, font=f_v, fill=accent+(255,))
    # 进度条
    by = y1-42
    d.rounded_rectangle([x0+30,by,x1-30,by+12], radius=6, fill=(228,234,248,255))
    d.rounded_rectangle([x0+30,by,x0+30+(x1-60)*clamp01(prog),by+12], radius=6, fill=accent+(255,))
    d.text((x0+30,by-40), subtitle, font=f_s, fill=INK_L+(255,))

_PHONE_CACHE = {}
def phone_frame(base, cx, cy, pw, ph, img=None, tilt=0.0, glow_color=(90,150,255), key=None):
    """手机壳 + 截图（带缓存；仅整体位置平移时复用）"""
    ck = (key, pw, ph, img is not None, glow_color)
    if ck not in _PHONE_CACHE:
        # 在本地画布绘制
        pad = int(pw*0.5)
        cw, ch = int(pw+pad*2), int(ph+pad*2)
        local = Image.new("RGBA",(cw,ch),(0,0,0,0))
        ox, oy = cw/2, ch/2
        x0,y0 = ox-pw/2, oy-ph/2
        box=[x0,y0,x0+pw,y0+ph]
        paste_glow(local, ox, oy, pw*0.9, glow_color, alpha=60)
        shadow = Image.new("RGBA",(cw,ch),(0,0,0,0))
        ImageDraw.Draw(shadow).rounded_rectangle([x0+10,y0+16,x0+pw+10,y0+ph+16],
                                                 radius=46, fill=(40,50,90,90))
        shadow = shadow.filter(ImageFilter.GaussianBlur(20))
        local.alpha_composite(shadow)
        d = ImageDraw.Draw(local)
        d.rounded_rectangle(box, radius=46, fill=(250,251,255,255))
        d.rounded_rectangle(box, radius=46, outline=(210,220,240,255), width=3)
        inset = 14
        sb = [x0+inset,y0+inset,x0+pw-inset,y0+ph-inset]
        if img is not None:
            im = img.copy().resize((int(sb[2]-sb[0]), int(sb[3]-sb[1])), Image.LANCZOS)
            mask = Image.new("L",(int(sb[2]-sb[0]),int(sb[3]-sb[1])),0)
            ImageDraw.Draw(mask).rounded_rectangle([0,0,sb[2]-sb[0],sb[3]-sb[1]],radius=34,fill=255)
            local.paste(im.convert("RGB"), (int(sb[0]),int(sb[1])), mask)
        else:
            d.rounded_rectangle(sb, radius=34, fill=(240,244,252,255))
        _PHONE_CACHE[ck] = (local, ox, oy)
    local, ox, oy = _PHONE_CACHE[ck]
    base.alpha_composite(local, (int(cx-ox), int(cy-oy)))
    return base

def badge(base, cx, cy, text, fg=WHITE, bg=BLUE_BRAND, padx=26, pady=12, r=20, fs=34):
    d = ImageDraw.Draw(base)
    f = font(fs)
    w = measure(d, text, f)[0]
    box=[cx-w/2-padx, cy-fs/2-pady, cx+w/2+padx, cy+fs/2+pady]
    d.rounded_rectangle(box, radius=r, fill=bg+(255,))
    d.text((cx,cy), text, font=f, fill=fg+(255,), anchor="mm")

def title_text(base, cx, y, s, fs=90, color=INK, stroke=0, stroke_color=(255,255,255), shadow=True, accent=None):
    d = ImageDraw.Draw(base)
    f = font(fs)
    if shadow:
        sh = Image.new("RGBA", base.size,(0,0,0,0))
        ImageDraw.Draw(sh).text((cx+4,y+5), s, font=f, fill=(90,110,170,110), anchor="mm")
        sh = sh.filter(ImageFilter.GaussianBlur(6))
        base.alpha_composite(sh)
        d = ImageDraw.Draw(base)
    if stroke:
        d.text((cx,y), s, font=f, fill=color+(255,), anchor="mm",
               stroke_width=stroke, stroke_fill=stroke_color+(255,))
    else:
        d.text((cx,y), s, font=f, fill=color+(255,), anchor="mm")
    if accent:
        w = measure(d, s, f)[0]
        d.rounded_rectangle([cx-w/2, y+fs*0.72, cx+w/2, y+fs*0.72+10], radius=5, fill=accent+(255,))
