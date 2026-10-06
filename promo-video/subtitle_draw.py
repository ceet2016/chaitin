# -*- coding: utf-8 -*-
"""字幕绘制：底部圆角底衬 + 白字，兼作「配音字幕」和「无障碍字幕」"""
from PIL import Image, ImageDraw, ImageFilter
from theme import font

PAD_X, PAD_Y = 34, 18
RADIUS = 22
BOTTOM_MARGIN = 64


def draw(base, text, fs=44, fill=(255, 255, 255), bg=(28, 34, 58), alpha=170):
    """在画面底部居中绘制一条字幕底衬"""
    if not text:
        return base
    d = ImageDraw.Draw(base)
    f = font(fs)
    box = d.textbbox((0, 0), text, font=f)
    tw, th = box[2] - box[0], box[3] - box[1]
    W, H = base.size
    cx = W // 2
    cy = H - BOTTOM_MARGIN - th // 2 - PAD_Y

    x0 = cx - tw // 2 - PAD_X
    x1 = cx + tw // 2 + PAD_X
    y0 = cy - th // 2 - PAD_Y
    y1 = cy + th // 2 + PAD_Y

    # 底衬：先做一层柔和投影，再压半透明圆角块，保证任何背景上都能读清
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([x0 + 3, y0 + 6, x1 + 3, y1 + 6],
                                         radius=RADIUS, fill=(20, 26, 46, 90))
    base.alpha_composite(sh.filter(ImageFilter.GaussianBlur(8)))

    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle([x0, y0, x1, y1], radius=RADIUS,
                                            fill=bg + (alpha,))
    base.alpha_composite(layer)

    d = ImageDraw.Draw(base)
    d.text((cx, cy), text, font=f, fill=fill + (255,), anchor="mm")
    return base
