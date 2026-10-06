# -*- coding: utf-8 -*-
"""背景特效：樱花瓣、星星、光带、网格"""
from PIL import Image, ImageDraw, ImageFilter
import math, random
from theme import *

def anime_sky(size, stops=None, stars=0, seed=1):
    w,h = size
    if stops is None:
        stops = [(0.0,(168,214,255)),(0.45,(210,232,255)),(0.75,(255,226,240)),(1.0,(255,208,228))]
    base = vgrad(size, None, None, stops=stops).convert("RGBA")
    rnd = random.Random(seed)
    if stars:
        d = ImageDraw.Draw(base)
        for _ in range(stars):
            x = rnd.uniform(0,w); y = rnd.uniform(0,h*0.7)
            r = rnd.uniform(1.2,3.2)
            a = rnd.randint(80,200)
            d.ellipse([x-r,y-r,x+r,y+r], fill=(255,255,255,a))
    return base

def sun_rays(base, cx, cy, n=18, r=1400, color=(255,255,240), alpha=40, t=0.0):
    layer = Image.new("RGBA", base.size, (0,0,0,0))
    d = ImageDraw.Draw(layer)
    for i in range(n):
        a = i*2*math.pi/n + t*0.25
        spread = 0.035
        p1 = (cx + math.cos(a-spread)*r, cy + math.sin(a-spread)*r)
        p2 = (cx + math.cos(a+spread)*r, cy + math.sin(a+spread)*r)
        d.polygon([(cx,cy), p1, p2], fill=color+(alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(6))
    base.alpha_composite(layer)

class Particles:
    """漂移粒子：花瓣 / 星星 / 光点"""
    def __init__(self, size, n=40, seed=7, kind="petal", color=None):
        self.w,self.h = size
        rnd = random.Random(seed)
        self.p = []
        for _ in range(n):
            self.p.append(dict(
                x=rnd.uniform(-100,self.w+100), y=rnd.uniform(-100,self.h+100),
                sp=rnd.uniform(20,70), drift=rnd.uniform(-40,40),
                phase=rnd.uniform(0,6.28), size=rnd.uniform(6,18),
                rot=rnd.uniform(0,6.28), rr=rnd.uniform(-1.5,1.5),
                alpha=rnd.randint(120,230)))
        self.kind = kind
        self.color = color or (255,190,214)

    def draw(self, base, dt):
        layer = Image.new("RGBA", base.size, (0,0,0,0))
        d = ImageDraw.Draw(layer)
        for p in self.p:
            p['phase'] += dt*1.6
            p['x'] += (p['drift'] + math.sin(p['phase'])*30)*dt
            p['y'] += p['sp']*dt
            p['rot'] += p['rr']*dt
            if p['y'] > self.h+60:
                p['y'] = -60; p['x'] = random.uniform(0,self.w)
            if p['x'] < -80: p['x'] = self.w+60
            if p['x'] > self.w+80: p['x'] = -60
            s = p['size']
            if self.kind=="petal":
                # 椭圆花瓣
                pet = Image.new("RGBA",(int(s*2.4),int(s*2.4)),(0,0,0,0))
                pd = ImageDraw.Draw(pet)
                pd.ellipse([s*0.2,s*0.5,s*2.0,s*1.7], fill=self.color+(p['alpha'],))
                pd.ellipse([s*0.6,s*0.7,s*1.8,s*1.4], fill=(255,255,255,p['alpha']//3))
                pet = pet.rotate(math.degrees(p['rot']), resample=Image.BICUBIC, expand=True)
                layer.alpha_composite(pet,(int(p['x']-s),int(p['y']-s)))
            elif self.kind=="star":
                sparkle(layer, p['x'], p['y'], s*0.6, self.color, p['alpha'], p['rot'])
            else:
                d.ellipse([p['x']-s*0.3,p['y']-s*0.3,p['x']+s*0.3,p['y']+s*0.3],
                          fill=self.color+(p['alpha'],))
        base.alpha_composite(layer)
        return base

_BOKEH_CACHE = {}
def _make_bokeh(size, n, seed, color):
    key = (size, n, seed, color)
    if key in _BOKEH_CACHE: return _BOKEH_CACHE[key]
    rnd = random.Random(seed)
    layer = Image.new("RGBA", size,(0,0,0,0))
    d = ImageDraw.Draw(layer)
    w,h = size
    for _ in range(n):
        x=rnd.uniform(0,w); y=rnd.uniform(0,h); r=rnd.uniform(30,110)
        d.ellipse([x-r,y-r,x+r,y+r], fill=color+(rnd.randint(14,40),))
    layer = layer.filter(ImageFilter.GaussianBlur(22))
    _BOKEH_CACHE[key] = layer
    return layer

def bokeh(base, n=14, seed=3, color=(255,255,255)):
    base.alpha_composite(_make_bokeh(base.size, n, seed, color))
    return base

def light_bands(base, t=0.0, color=(255,255,255), alpha=30):
    w,h = base.size
    layer = Image.new("RGBA", base.size,(0,0,0,0))
    d = ImageDraw.Draw(layer)
    for i in range(3):
        y = h*(0.2+0.3*i) + math.sin(t*0.8+i)*40
        d.polygon([(0,y),(w,y-120),(w,y-60),(0,y+60)], fill=color+(alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(30))
    base.alpha_composite(layer)
    return base
