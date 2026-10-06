# -*- coding: utf-8 -*-
"""字幕：按解说词切条，断行时优先落在标点处，且不切断西文单词

断行策略（三级降级，保证读起来不断词）：
  1. 在标点（，、；：。！？）处断 —— 最自然
  2. 在「西文单词 / 数字串」边界断 —— 不劈开 Kotlin / AES-256-GCM
  3. 实在放不下才按宽度硬断
另外做一次「孤行回补」，避免出现只有一两个字的尾巴。
"""
import json, os, re, sys

MAXW = 19          # 单行视觉宽度上限（中文 1.0，西文 0.55）
SOFT = "，、；："
HARD = "。！？；"


def _w(s):
    return sum(1.0 if ord(c) > 0x2E80 else 0.55 for c in s)


def _tokens(text):
    """不可分割的原子块：西文/数字连写为一块，其余逐字"""
    return [t for t in re.findall(r"[A-Za-z0-9][A-Za-z0-9.\-_/+]*|[^A-Za-z0-9]", text) if t]


def _break_points(text):
    """返回可断位置集合（字符下标，断在该下标之前）"""
    pts = set()
    for i, ch in enumerate(text):
        if ch in SOFT + HARD and i + 1 < len(text):
            pts.add(i + 1)
    # 西文 token 之后也是可断点
    for tk in _tokens(text):
        pass
    for m in re.finditer(r"[A-Za-z0-9][A-Za-z0-9.\-_/+]*", text):
        pts.add(m.end())
    return pts


def wrap(text, maxw=MAXW):
    """贪心断行：优先在标点处断，避免断词"""
    # 第一步：按句末标点切成大块
    blocks = [b for b in re.split(r"(?<=[" + HARD + r"])", text) if b.strip()]
    lines = []
    for blk in blocks:
        blk = blk.strip()
        while _w(blk) > maxw:
            bp = _break_points(blk)
            # 在 maxw 内找最靠右的可断点
            cand = [p for p in bp if p < len(blk) and _w(blk[:p]) <= maxw]
            if cand:
                cut = max(cand)
            else:
                # 无可断点：退化为按 token 累积
                acc = ""
                for tk in _tokens(blk):
                    if _w(acc + tk) > maxw and acc:
                        break
                    acc += tk
                cut = len(acc) if acc else maxw
            lines.append(blk[:cut].strip())
            blk = blk[cut:].strip()
        if blk:
            lines.append(blk)
    return _rebalance(lines, maxw)


def _rebalance(lines, maxw):
    """回补孤行：某行过短时，把上一行尾部按可断点挪过来"""
    for _ in range(2):
        for i in range(1, len(lines)):
            if _w(lines[i]) >= maxw * 0.5 or not lines[i - 1]:
                continue
            prev = lines[i - 1]
            bp = sorted(p for p in _break_points(prev))
            # 从右往左找能借的位置，使本行过半宽
            for p in reversed(bp):
                moved, rest = prev[p:], prev[:p]
                if not rest:
                    continue
                if _w(moved + lines[i]) <= maxw + 2.0:
                    lines[i - 1] = rest
                    lines[i] = moved + lines[i]
                    break
    return [x for x in (y.strip() for y in lines) if x]


def layout(text, at, dur, gap, min_dur=1.15, max_dur=6.5):
    span = max(0.5, dur - at - gap)
    chunks = wrap(text)
    if not chunks:
        return []
    tot = sum(_w(c) for c in chunks) or 1.0
    items, cur = [], float(at)
    for c in chunks:
        d = span * _w(c) / tot
        d = max(min_dur, min(max_dur, d))
        items.append([round(cur, 2), round(cur + d, 2), c])
        cur += d
    if items[-1][1] > at + span:
        scale = span / max(0.01, items[-1][1] - items[0][0])
        t0 = items[0][0]
        for it in items:
            it[0] = round(t0 + (it[0] - t0) * scale, 2)
            it[1] = round(t0 + (it[1] - t0) * scale, 2)
    return items


def build_all(narration, scenes, out_json=None):
    subs = {}
    for sid, s in scenes.items():
        key = s.get("voice")
        if not key:
            continue
        n = narration[key]
        subs[sid] = layout(n["text"], n["at"], s["dur"], n["gap"])
    if out_json:
        os.makedirs(os.path.dirname(out_json), exist_ok=True)
        json.dump(subs, open(out_json, "w"), ensure_ascii=False, indent=1)
    return subs


def active(subs, t_local):
    for a, b, txt in subs:
        if a <= t_local < b:
            return txt
    return None


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from voice import NARRATION
    import timeline as TL
    d = sys.argv[1] if len(sys.argv) > 1 else "/tmp/work/.video/narration"
    scenes, total = TL.load(d)
    s = build_all(NARRATION, scenes, os.path.join(os.path.dirname(d), "subtitles.json"))
    n = 0
    for sid in TL.SCENE_ORDER:
        for a, b, txt in s.get(sid, []):
            print("%-6s %5.2f-%5.2f  %s" % (sid, a, b, txt))
            n += 1
    print("---\n共 %d 条字幕" % n)
