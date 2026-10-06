# -*- coding: utf-8 -*-
"""逐帧渲染器：把分镜渲染成 PNG 序列"""
import sys, os, math, random
sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from theme import *
import character, bgfx, ui
from storyboard import SCENES
import subtitle_draw, subtitles
import json

SHOTS = {}
SUBS = {}
FRAMES_DIR = os.environ.get("VIDEO_FRAMES", "/workspace/.video/frames")
SHOT_DIR = os.environ.get("VIDEO_SHOTS", "/workspace/docs")

def load_subs(path=None):
    path = path or os.path.join(os.path.dirname(FRAMES_DIR), "subtitles.json")
    global SUBS
    if os.path.exists(path):
        SUBS = json.load(open(path))
def load_shots():
    for name in ("accounts","login","dashboard"):
        p = os.path.join(SHOT_DIR, f"screenshot-{name}.jpg")
        if os.path.exists(p):
            SHOTS[name] = Image.open(p).convert("RGB")

# ---------- 通用装饰 ----------
def deco_particles(base, part, t):
    part.draw(base, 1/FPS)

def corner_stars(base, t, n=6, seed=11):
    rnd = random.Random(seed)
    for i in range(n):
        x = rnd.uniform(80, W-80); y = rnd.uniform(60, H-80)
        ph = rnd.uniform(0,6.28)
        s = 6 + 8*(0.5+0.5*math.sin(t*2+ph))
        a = int(120+120*(0.5+0.5*math.sin(t*2.5+ph)))
        sparkle(base, x, y, s, WHITE, a, rot=t*0.5+ph)

def scene_frame(base, t_local, t_global, rnd, part):
    """背景公共层"""
    pass

# ============ 场景渲染 ============
def s_open(base, t, d):
    # 光晕 + 太阳射线
    bgfx.sun_rays(base, W*0.5, H*0.42, n=20, r=1500,
                  color=(255,255,250), alpha=30, t=t)
    # 徽记
    cx, cy = W/2, H*0.34
    r = 108 + math.sin(t*2)*5
    character_shield(base, cx, cy, r, t)
    # 标题（弹入）
    p = smoothstep(t/0.9)
    yoff = (1-p)*40
    ui.title_text(base, cx, H*0.60+yoff, d["main"], fs=118, color=INK,
                  stroke=3, stroke_color=(255,255,255), accent=PINK)
    sp = smoothstep((t-0.5)/0.8)
    if sp>0:
        ui.title_text(base, cx, H*0.72+(1-sp)*24, d["sub"], fs=50, color=INK_L)
    # tag
    tp = smoothstep((t-1.1)/0.7)
    if tp>0:
        d2 = ImageDraw.Draw(base)
        f = font(30)
        d2.text((cx, H*0.155), d["tag"], font=f,
                fill=(255,255,255,255), anchor="mm",
                stroke_width=1, stroke_fill=INK_L+(255,))
    corner_stars(base, t, 8)

def character_shield(base, cx, cy, r, t):
    """盾牌徽记（发光）"""
    paste_glow(base, cx, cy, r*1.7, (110,160,255), alpha=110)
    lay = Image.new("RGBA", base.size, (0,0,0,0))
    dl = ImageDraw.Draw(lay)
    pts = [(cx-r, cy-r*0.92),(cx+r, cy-r*0.92),(cx+r*0.94, cy+r*0.22),
           (cx, cy+r*1.06),(cx-r*0.94, cy+r*0.22)]
    dl.polygon(pts, fill=(255,255,255,255))
    dl.polygon([(cx-r*0.78, cy-r*0.68),(cx+r*0.78, cy-r*0.68),(cx+r*0.72, cy+r*0.16),
                (cx, cy+r*0.80),(cx-r*0.72, cy+r*0.16)], fill=BLUE_BRAND+(255,))
    dl.polygon([(cx, cy-r*0.42),(cx+r*0.36, cy-r*0.42),(cx, cy+r*0.40),(cx-r*0.36, cy-r*0.42)],
               fill=(255,255,255,255))
    # 内高光
    dl.polygon([(cx-r*0.72, cy-r*0.62),(cx, cy-r*0.62),(cx-r*0.6, cy+r*0.1)],
               fill=(90,140,240,255))
    base.alpha_composite(lay)
    # 环旋小星
    for i in range(4):
        a = t*1.2 + i*math.pi/2
        sparkle(base, cx+math.cos(a)*r*1.5, cy+math.sin(a)*r*1.5, 8,
                WHITE, 200, rot=a)

def _subtitle(base, y, text, prog=1.0, fs=56, color=(255,255,255), box=True):
    """底部字幕条，带淡入"""
    d = ImageDraw.Draw(base)
    f = font(fs)
    w = measure(d, text, f)[0]
    if box:
        lay = Image.new("RGBA", base.size,(0,0,0,0))
        ld = ImageDraw.Draw(lay)
        pad = 34
        ld.rounded_rectangle([W/2-w/2-pad, y-46, W/2+w/2+pad, y+46],
                             radius=26, fill=(30,40,80,150))
        base.alpha_composite(lay)
    d = ImageDraw.Draw(base)
    d.text((W/2, y), text, font=f, fill=color+(255,), anchor="mm")

def s_chara_talk(base, t, d):
    # 背景
    bgfx.bokeh(base, 12, seed=5)
    # 角色（左/中）
    chara_x = W*0.30
    blink = (int(t*1.3)%5==0) and (t%1<0.12)
    mouth = 0.7 if (int(t*3)%2==0 and t%0.5<0.3) else 0.2
    character.draw_character(base, chara_x, H*0.46, 1.55, t, blink,
                             math.sin(t*0.7)*0.4, mouth, wave=0.6*max(0,math.sin(t*0.5)))
    # 对话气泡
    lines = d.get("lines", [])
    title = d.get("title")
    shown = None; lp = 0
    for (start, txt) in lines:
        if t >= start:
            shown = txt
            lp = clamp01((t-start)/0.5)
    if title and t>0:
        ui.title_text(base, W*0.66, H*0.22, title, fs=64, color=BLUE_BRAND,
                      stroke=2, stroke_color=(255,255,255))
    if shown:
        # 气泡
        bx0, by0, bx1, by1 = W*0.50, H*0.36, W*0.94, H*0.62
        al = int(235*lp)
        glass_card(base, [bx0, by0, bx1, by1], radius=32, alpha=al, tint=(255,255,255))
        # 小尾巴
        d2 = ImageDraw.Draw(base)
        d2.polygon([(bx0+6, H*0.47),(bx0-36, H*0.50),(bx0+6, H*0.53)],
                   fill=(255,255,255,al))
        f = font(52)
        dl = ImageDraw.Draw(base)
        ls = wrap_cjk(shown, f, (bx1-bx0)-100)
        total_h = len(ls)*(f.size+16)
        yy = (by0+by1)/2 - total_h/2 + f.size/2
        for ln in ls:
            dl.text((bx0+50, yy), ln, font=f, fill=INK+(int(255*lp),))
            yy += f.size+16

def s_points(base, t, d):
    tp = smoothstep(t/0.7)
    ui.title_text(base, W/2, H*0.16, d["title"], fs=84, color=INK,
                  stroke=3, stroke_color=(255,255,255), accent=d.get("accent",PINK))
    pts = d["points"]
    n = len(pts)
    for i, p in enumerate(pts):
        st = 0.5 + i*0.7
        pr = smoothstep((t-st)/0.6)
        if pr<=0: continue
        y = H*0.33 + i*H*0.155
        x0 = W*0.14 + (1-ease_out(pr))*60
        x1 = W*0.86
        glass_card(base, [x0, y-56, x1, y+56], radius=26, alpha=int(230*pr))
        dl = ImageDraw.Draw(base)
        # 圆形序号
        cxx = x0+66
        dl.ellipse([cxx-32,y-32,cxx+32,y+32], fill=(PINK if i%2 else SKY_D)+(int(255*pr),))
        dl.text((cxx,y), str(i+1), font=font(38), fill=WHITE+(int(255*pr),), anchor="mm")
        f = font(46)
        dl.text((x0+124, y), p, font=f, fill=INK+(int(255*pr),), anchor="lm")
    if d.get("chara"):
        character.draw_character(base, W*0.90, H*0.84, 0.62, t, False, -0.7, 0.3)

def s_feature_phone(base, t, d):
    bgfx.bokeh(base, 10, seed=8)
    # 左侧文案
    ui.title_text(base, W*0.28, H*0.13, d["title"], fs=74, color=BLUE_BRAND,
                  stroke=3, stroke_color=(255,255,255))
    dl = ImageDraw.Draw(base)
    dl.text((W*0.28, H*0.22), d["sub"], font=font(44), fill=INK_L+(255,), anchor="mm")
    pts = d["points"]
    for i,p in enumerate(pts):
        st = 0.4+i*0.6
        pr = smoothstep((t-st)/0.6)
        if pr<=0: continue
        y = H*0.36 + i*H*0.16
        x = W*0.05 - (1-ease_out(pr))*40
        dl = ImageDraw.Draw(base)
        # 对勾
        dl.ellipse([x, y-20, x+40, y+20], fill=(120,230,210,int(255*pr)))
        dl.line([(x+10,y),(x+18,y+9),(x+31,y-9)], fill=WHITE+(int(255*pr),), width=6)
        f = font(40)
        for j,ln in enumerate(wrap_cjk(p, f, W*0.40)):
            dl.text((x+58, y-16+j*46), ln, font=f, fill=INK+(int(255*pr),))
    # 右侧手机
    shot = SHOTS.get(d.get("shot"))
    pp = smoothstep((t-0.2)/0.9)
    if pp>0:
        ph = H*0.82
        pw = ph*0.50
        cy = H*0.52 + (1-ease_out(pp))*H*0.15
        cx = W*0.72
        ui.phone_frame(base, cx, cy, pw, ph, shot, glow_color=(90,150,255), key=d.get("shot"))
        # 漂浮标签
        for k,(tt,dy) in enumerate([(d.get("sub",""),0)]):
            pass
    corner_stars(base, t, 5, seed=4)

def s_modgrid(base, t, d):
    ui.title_text(base, W/2, H*0.12, d["title"], fs=80, color=INK,
                  stroke=3, stroke_color=(255,255,255), accent=PINK)
    dl = ImageDraw.Draw(base)
    dl.text((W/2, H*0.205), d["sub"], font=font(42), fill=INK_L+(255,), anchor="mm")
    mods = [("多账号切换","🔀",SKY_D),("仪表盘","📊",PINK),("防护站点","🛡",PURPLE),
            ("IP 组","🧩",MINT),("黑白名单","📋",GOLD),("自定义规则","⚙",(255,140,120)),
            ("频率限制 CC","🚦",SKY_D),("日志中心","📜",PINK),("证书管理","🔐",PURPLE)]
    cols, rows = 3, 3
    gx, gy = W*0.07, H*0.28
    cw = (W - gx*2 - 60*2)/cols
    ch = (H*0.66 - 60*2)/rows
    for i,(name,emo,ac) in enumerate(mods):
        r = i//cols; c = i%cols
        st = 0.3 + i*0.16
        pr = smoothstep((t-st)/0.5)
        if pr<=0: continue
        x0 = gx + c*(cw+60); y0 = gy + r*(ch+60)
        sj = (1-ease_out(pr))*30
        glass_card(base, [x0, y0+sj, x0+cw, y0+ch+sj], radius=24, alpha=int(232*pr))
        cxx, cyy = x0+cw/2, y0+ch/2+sj
        paste_glow(base, cxx, cyy-24, 54, ac, alpha=int(70*pr))
        dl = ImageDraw.Draw(base)
        dl.text((cxx, cyy-26), emo, font=font(64), fill=(0,0,0,0), anchor="mm")
        # 用色块代替 emoji（WQY 可能缺表情字形）
        dl.rounded_rectangle([cxx-30, cyy-52, cxx+30, cyy+8], radius=16, fill=ac+(int(255*pr),))
        dl.text((cxx, cyy+50), name, font=font(38), fill=INK+(int(255*pr),), anchor="mm")

def s_download(base, t, d):
    ui.title_text(base, W/2, H*0.20, d["title"], fs=96, color=INK,
                  stroke=3, stroke_color=(255,255,255), accent=SKY_D)
    dl = ImageDraw.Draw(base)
    dl.text((W/2, H*0.32), d["sub"], font=font(46), fill=INK_L+(255,), anchor="mm")
    # 地址卡
    pr = smoothstep((t-0.5)/0.8)
    if pr>0:
        glass_card(base, [W*0.20, H*0.42, W*0.80, H*0.55], radius=28, alpha=int(235*pr))
        dl = ImageDraw.Draw(base)
        dl.text((W/2, H*0.485), "cnb.cool/unicom/chaitin", font=font(60),
                fill=SKY_D+(int(255*pr),), anchor="mm")
    # 下载按钮
    bp = smoothstep((t-1.0)/0.7)
    if bp>0:
        bx0,by0,bx1,by1 = W*0.34, H*0.62, W*0.66, H*0.74
        pulse = 1+0.03*math.sin(t*3)
        cx,cy = (bx0+bx1)/2,(by0+by1)/2
        w2,h2 = (bx1-bx0)*pulse,(by1-by0)*pulse
        dl = ImageDraw.Draw(base)
        dl.rounded_rectangle([cx-w2/2,cy-h2/2,cx+w2/2,cy+h2/2], radius=int(h2/2),
                             fill=BLUE_BRAND+(int(255*bp),))
        dl.text((cx,cy), "下载最新 APK", font=font(50), fill=WHITE+(int(255*bp),), anchor="mm")
    # 角色
    character.draw_character(base, W*0.84, H*0.72, 0.72, t, False, -0.5, 0.4, wave=0.8)

def s_endcard(base, t, d):
    character.draw_character(base, W*0.24, H*0.50, 1.0, t, False, 0.4, 0.5,
                             wave=0.7*max(0,math.sin(t*0.8)))
    # 右侧文字块
    cx = W*0.66
    ui.title_text(base, cx, H*0.40, d["main"], fs=104, color=INK,
                  stroke=4, stroke_color=(255,255,255), accent=PINK)
    sp = smoothstep((t-0.5)/0.8)
    if sp>0:
        ui.title_text(base, cx, H*0.52+(1-sp)*24, d["sub"], fs=50, color=INK_L,
                      stroke=3, stroke_color=(255,255,255))
    up = smoothstep((t-1.0)/0.8)
    if up>0:
        ui.badge(base, cx, H*0.66, d["url"], fs=36, bg=BLUE_BRAND)
    corner_stars(base, t, 10, seed=9)

RENDERERS = dict(title=s_open, chara_talk=s_chara_talk, points=s_points,
                 feature_phone=s_feature_phone, modgrid=s_modgrid,
                 download=s_download, endcard=s_endcard)

# ---------- 多进程主渲染 ----------
def _worker(args):
    si, sc = args
    import os
    from PIL import Image
    W,H,FPS = 1920,1080,30
    nf = int(round(sc["dur"]*FPS))
    stops = {
      "open":  [(0,(160,205,255)),(0.5,(216,236,255)),(1,(255,214,232))],
      "hook":  [(0,(64,84,146)),(0.5,(118,108,178)),(1,(198,140,190))],
      "intro": [(0,(150,195,255)),(0.5,(205,228,255)),(1,(255,208,230))],
      "safe":  [(0,(150,220,210)),(0.5,(205,240,235)),(1,(226,244,255))],
      "end":   [(0,(255,190,215)),(0.5,(255,226,240)),(1,(214,234,255))],
    }.get(sc["id"], [(0,(178,218,255)),(0.5,(224,238,255)),(1,(255,224,240))])
    stars = 60 if sc["id"]=="hook" else 0
    bg = bgfx.anime_sky((W,H), stops=stops, stars=stars, seed=si+1)
    pcolor = (205,215,255) if sc["id"]=="hook" else (255,196,220)
    part = bgfx.Particles((W,H), n=30, seed=si*7+3, kind="petal", color=pcolor)
    renderer = RENDERERS[sc["kind"]]
    outdir = os.path.join(FRAMES_DIR, f"s{si:02d}")
    os.makedirs(outdir, exist_ok=True)
    for f in range(nf):
        t = f/FPS
        frame = bg.copy()
        try:
            renderer(frame, t, sc)
        except Exception as e:
            ImageDraw.Draw(frame).text((W/2,H/2), f"ERR {sc['id']}: {e}",
                                       font=font(40), fill=(255,0,0), anchor="mm")
        part.draw(frame, 1/FPS)
        sc_subs = SUBS.get(sc["id"]) or []
        txt = subtitles.active(sc_subs, t)
        if txt:
            subtitle_draw.draw(frame, txt)
        frame.convert("RGB").save(f"{outdir}/{f:05d}.jpg", quality=92)
    return si, nf

def render_all(outdir=None):
    global FRAMES_DIR
    outdir = outdir or FRAMES_DIR
    FRAMES_DIR = outdir
    import time, multiprocessing as mp
    os.makedirs(outdir, exist_ok=True)
    load_shots()
    load_subs()
    t0 = time.time()
    jobs = list(enumerate(SCENES))
    with mp.Pool(8) as pool:
        results = pool.map(_worker, jobs)
    print("render done in %.1fs" % (time.time()-t0))
    return results

if __name__ == "__main__":
    import json
    res = render_all()
    meta = []
    idx = 0
    for si, sc in enumerate(SCENES):
        nf = int(round(sc["dur"]*FPS))
        meta.append(dict(id=sc["id"], si=si, n=nf, dur=sc["dur"]))
        idx += nf
    json.dump(meta, open(os.path.join(FRAMES_DIR, "meta.json"),"w"),
              ensure_ascii=False, indent=1)
    print("TOTAL", idx, idx/FPS, "s")
