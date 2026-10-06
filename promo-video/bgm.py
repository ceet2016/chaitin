# -*- coding: utf-8 -*-
"""成片配乐：原创轻音乐《为你的防线，站好每一班岗》(v4 重做)

为什么第 4 次重做（前三次依次解决了「电子琴味」「编曲散架」「节拍器感」）：

  用户反馈 v3「背景音乐，尤其是才开头的部分，特别刺耳」。
  把 v3 的开头单独拆出来量，问题很清楚：
    * 0~19 秒几乎没有低频（<250Hz 能量占比 0.2%~6%），
      整段只剩弦乐在中高频晃，频谱质心 2000~2600Hz ——「又薄又尖」。
    * 开头用的是 `tubular_bell`（管钟）当主音，它的起音是一段
      2.5kHz 的噪声撞击 + 3.76/4.07 倍的非谐分音，本身就带「金属尖头」。
    * 全片在 2~4kHz 有持续能量，配上 -16 LUFS 的响度，长时间听会累。

这次改的是「配器思路」，不是再挂效果器：

  1. **开头就要有底**：从第 0 秒起就铺一层低音铺底（Rhodes 电钢 + 低音弦乐），
     低频占比拉到 20% 以上，声音不再飘。
  2. **换掉管钟**：前奏主音改成「柔和电钢 + 钢片琴」，起音是软渐入，
     非谐分音收窄、高次分音砍掉，听感是「棉」不是「金属」。
  3. **整体压高频**：母线做 4.5kHz 以上搁架衰减 + 6.8kHz 低通，
     再把 2.6kHz 那个「齿音区」压 2.5dB，从根上削掉刺。
  4. **力度更轻**：全曲动态收窄到 3 档，段落靠「加不加元素」而不是「加大音量」推进。
  5. **人声优先**：旋律给人声整块让位，闪避降到 5.5dB（够用又不发闷）。
"""
import numpy as np
import sys, os, time

from dsp import (SR, add, place, normalize, fade, to_sr, stereo_widen, soft_sat,
                 dither, note, highpass, lowpass, peak_eq, high_shelf,
                 sidechain_gain, rms_env, smooth_env, tape_sat)
import instruments as I


class Chord:
    __slots__ = ("root", "notes", "bass", "name")

    def __init__(self, root, notes, bass, name):
        self.root, self.notes, self.bass, self.name = root, notes, bass, name


F, C, D, Bb, G, A, B = 5, 0, -2, -5, -7, -10, -9

# 8 小节一循环，低音线级进（F-G-A-Bb-C-C-F-Bb）
PROG = [
    Chord(F,  [5, 9, 12, 16],      5,  "Fmaj7"),
    Chord(C,  [5, 7, 11, 14],      2,  "Cadd9"),
    Chord(D,  [-2, 2, 5, 9],       0,  "Dm7"),
    Chord(Bb, [-5, -1, 2, 5],      -3, "Bbadd9"),
    Chord(C,  [4, 7, 11, 14],      -3, "C7"),
    Chord(F,  [5, 9, 12, 16],      5,  "Fmaj7"),
    Chord(D,  [-2, 2, 5, 9],       -2, "Dm7"),
    Chord(Bb, [-5, -1, 2, 5],      -5, "Bb"),
]

BPM = 74.0
BEAT = 60.0 / BPM
BAR = BEAT * 4

# (id, 时长, 层数 0..2, 主导动机, 是否有鼓)
SECTIONS = [
    ("open",    9.20,  0, "intro",  False),
    ("hook",    5.80,  1, "motif",  False),
    ("intro",  10.10,  1, "motif",  False),
    ("what",   17.20,  2, "motif",  True),
    ("feat1",  24.60,  2, "travel", True),
    ("feat2",  25.30,  2, "motif",  True),
    ("feat3",  21.80,  2, "travel", True),
    ("mods",   19.00,  2, "motif",  True),
    ("log",    27.90,  1, "lament", True),
    ("safe",   16.60,  0, "awe",    False),
    ("dl",     11.80,  2, "motif",  True),
    ("end",     7.40,  1, "coda",   False),
]


def chord_of(bar):
    return PROG[bar % len(PROG)]


def _layer_table():
    marks, acc = [], 0.0
    for sid, dur, layer, lead, drums in SECTIONS:
        marks.append((acc, acc + dur, sid, layer, lead, drums))
        acc += dur
    return marks, acc


def section_at(t, marks):
    for a, b, sid, layer, lead, drums in marks:
        if a <= t < b:
            inner = (t - a) / max(0.001, b - a)
            return sid, layer, lead, drums, inner
    return SECTIONS[-1][0], SECTIONS[-1][2], SECTIONS[-1][3], SECTIONS[-1][4], 1.0


def _phrase_amp(bar_in_section, ramp=4):
    return min(1.0, (bar_in_section + 1) / float(ramp))


# ---------- 声部 ----------

def build_lowpad(buf, total, marks):
    """低音铺底：Rhodes 电钢 + 低音弦乐，从第 0 秒起就有「底」。
    这是 v4 最关键的一条——v3 的开头之所以刺耳，就是因为它是空的。"""
    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        sid, layer, lead, drums, inner = section_at(t0, marks)
        ch = chord_of(bar)
        amp = 0.11 + 0.02 * layer
        if lead == "lament":
            amp *= 0.85
        # 低音弦乐（比主和声低一个八度）
        freqs = [note(ch.bass - 12), note(ch.bass - 5)]
        freqs = [f for f in freqs if 45 < f < 400]
        if freqs:
            sig = I.strings(freqs, BAR * 2 + 1.2, amp=amp * 0.9, cutoff=1800,
                            swell=1.2 if bar == 0 else 0.0)
            sig = I.with_room(sig, wet=0.34, decay=0.9, predelay=0.03,
                              seed=31 + bar, damp=3600)
            add(buf, sig, to_sr(t0))
        # Rhodes 电钢：温暖的中低音区，短促音头，像「垫子」不像「主奏」
        for k, (off, deg) in enumerate(((0.0, 0), (BEAT * 2.5, 1), (BEAT * 3.5, 2))):
            t = t0 + off
            if t >= total:
                break
            nn = ch.notes[deg % len(ch.notes)]
            while note(nn) > 500:
                nn -= 12
            sig = I.rhodes(note(nn), 2.2, (0.085 + 0.015 * layer) * (1.0 - 0.2 * k))
            sig = I.with_room(sig, wet=0.30, decay=0.6, predelay=0.02,
                              seed=bar * 17 + k, damp=3200)
            add(buf, sig, to_sr(t))
        bar += 1


def build_pad(buf, total, marks, lead_of_bar):
    """弦乐和声：两小节一换，力度克制"""
    bar = 0
    while bar * BAR < total:
        sid, layer, lead, drums, inner = section_at(bar * BAR, marks)
        ch = chord_of(bar)
        freqs = [note(s + 12) for s in ch.notes if note(s + 12) < 2200]
        amp = 0.045 + 0.022 * layer
        if lead == "lament":
            amp *= 0.75
        dur = BAR * 2 + 1.0
        swell = 0.0 if bar else 1.8
        sig = I.strings(freqs, dur, amp=amp, cutoff=1900 + 250 * layer, swell=swell)
        sig = I.with_room(sig, wet=0.40, decay=0.95, predelay=0.035, seed=11 + bar,
                          damp=4200)
        add(buf, sig, to_sr(bar * BAR))
        bar += 2


def build_bass(buf, total, marks):
    """电贝斯：根音 + 五音点，2、4 拍走动"""
    bar = 0
    while bar * BAR < total:
        sid, layer, lead, drums, inner = section_at(bar * BAR, marks)
        ch = chord_of(bar)
        amp = 0.17 + 0.04 * layer
        if lead == "lament":
            amp *= 0.82
        root = ch.bass
        f_root = note(root - 24)
        if f_root < 44:
            f_root = note(root - 12)
        add(buf, I.bass(f_root, BAR * 0.90, amp * 0.95, finger=1.0), to_sr(bar * BAR))
        if layer >= 1:
            add(buf, I.bass(note(ch.notes[2] - 24 if ch.notes[2] - 24 > -30 else ch.notes[2] - 12),
                            BAR * 0.40, amp * 0.58), to_sr(bar * BAR + BEAT * 2))
        if layer >= 2:
            add(buf, I.bass_pick(note(root - 12), BAR * 0.34, amp * 0.42),
                to_sr(bar * BAR + BEAT * 2.5))
        bar += 1


def build_drums(buf, total, marks):
    """原声鼓组：4/4「咚—哒」，全程轻，只为节拍不抢耳"""
    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        sid, layer, lead, drums, inner = section_at(t0, marks)
        if not drums or layer == 0:
            bar += 1
            continue
        ramp = _phrase_amp(bar % 4, 3)
        amp = (0.52 + 0.13 * layer) * ramp
        add(buf, I.kick(0.5, 0.24 * amp), to_sr(t0))
        add(buf, I.kick(0.5, 0.20 * amp), to_sr(t0 + BEAT * 2))
        if layer >= 2:
            add(buf, I.kick(0.45, 0.13 * amp), to_sr(t0 + BEAT * 2.5))
        for b in (1, 3):
            a = (0.10 if layer >= 2 else 0.065) * amp
            add(buf, I.snare(0.28, a), to_sr(t0 + b * BEAT))
            add(buf, I.snare(0.10, a * 0.26, ghost=True), to_sr(t0 + b * BEAT + BEAT * 0.5))
        add(buf, I.shaker(0.12, 0.022 * amp), to_sr(t0 + BEAT * 0.5))
        add(buf, I.shaker(0.12, 0.018 * amp), to_sr(t0 + BEAT * 1.5))
        add(buf, I.shaker(0.12, 0.020 * amp), to_sr(t0 + BEAT * 2.5))
        add(buf, I.shaker(0.12, 0.016 * amp), to_sr(t0 + BEAT * 3.5))
        if inner > 0.9:
            add(buf, I.hat(0.26, 0.036 * amp, open_=True), to_sr(t0 + BEAT * 3.5))
        bar += 1


def build_arp(buf, total, marks, narration_marks):
    """分解和弦：柔和电钢，密度随层数；人声满句时退后"""
    eighths = [0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5]
    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        sid, layer, lead, drums, inner = section_at(t0, marks)
        ch = chord_of(bar)
        SHAPES = {0: [], 1: [[0, 1, 2]], 2: [[0, 2, 3], [0, 1, 2, 3, 2, 1, 2, 3]]}
        shape = SHAPES[layer][bar % len(SHAPES[layer])] if layer else []
        amp = (0.055 + 0.015 * layer) * _phrase_amp(bar % 4, 3)
        if narration_marks(t0, t0 + BAR) > 0.6:
            amp *= 0.5
        for i, k in enumerate(shape):
            t = t0 + eighths[i] * BEAT * 2 if i < len(eighths) else t0
            if t >= total:
                break
            n = ch.notes[k % len(ch.notes)] + (12 if k % 4 < 2 else 24)
            jitter = 0.012 * np.sin(bar * 2.7 + i)
            sig = I.rhodes(note(n), 2.0, amp * (1.0 if i % 2 == 0 else 0.76))
            sig = I.with_room(sig, wet=0.26, decay=0.5, predelay=0.012,
                              seed=bar * 31 + i, damp=3200)
            add(buf, sig, to_sr(t + jitter))
        bar += 1


def build_melody(buf, total, marks, narration_marks):
    """主旋律：柔和电钢 + 钢片琴点落点，只在人声留白处出现。
    v3 用的是管钟，起音带 2.5kHz 噪声撞击，是「刺」的主要来源，本版弃用。"""
    MOTIF = [(0, 0.0, 2, 1.2, 0.85, False), (0, 1.5, 3, 0.6, 0.58, False),
             (1, 0.0, 4, 1.6, 0.78, False), (1, 2.0, 5, 1.0, 0.54, True),
             (2, 0.0, 4, 1.2, 0.74, False), (2, 1.5, 3, 0.6, 0.52, False),
             (3, 0.0, 2, 2.4, 0.85, False), (3, 2.5, 1, 0.8, 0.48, False)]
    TRAVEL = [(0, 0.0, 4, 1.0, 0.80, False), (0, 1.0, 5, 0.5, 0.54, False),
              (1, 0.0, 5, 1.2, 0.72, False), (1, 2.0, 4, 0.8, 0.50, False),
              (2, 0.0, 3, 1.4, 0.74, False), (2, 2.0, 3, 0.8, 0.52, False),
              (3, 0.0, 2, 2.0, 0.78, True), (3, 2.0, 3, 1.0, 0.48, False)]
    LAMENT = [(0, 0.0, 2, 1.6, 0.66, False), (1, 0.0, 3, 1.0, 0.52, False),
              (2, 0.0, 4, 2.0, 0.64, False), (3, 0.0, 3, 1.4, 0.52, False)]
    AWE = [(0, 0.0, 2, 3.0, 0.60, False), (2, 0.0, 4, 3.0, 0.50, False)]
    CODA = [(0, 0.0, 2, 2.0, 0.72, False), (1, 0.0, 3, 1.2, 0.54, False),
            (2, 0.0, 4, 2.6, 0.68, False), (3, 0.0, 2, 3.2, 0.60, False)]
    INTRO = [(0, 0.0, 2, 2.4, 0.60, False), (1, 2.0, 3, 1.2, 0.44, False),
             (2, 0.0, 4, 2.4, 0.56, False), (3, 1.0, 2, 2.0, 0.48, True)]
    PATTERNS = dict(motif=MOTIF, travel=TRAVEL, lament=LAMENT, awe=AWE,
                    coda=CODA, intro=INTRO)

    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        sid, layer, lead, drums, inner = section_at(t0, marks)
        if narration_marks(t0, t0 + BAR * 4) > 0.45:
            bar += 4
            continue
        pat = PATTERNS.get(lead, MOTIF)
        for bo, beat, deg, dur, amp, bell in pat:
            t = t0 + bo * BAR + beat * BEAT
            if t >= total:
                continue
            ch = chord_of(bar + bo)
            n = ch.notes[deg % len(ch.notes)] + (12 if deg < 4 else 24)
            a = amp * (0.85 + 0.15 * layer)
            if bell:
                sig = I.glock(note(n), 1.3, 0.045 * a)
            else:
                sig = I.rhodes(note(n), 2.4, 0.12 * a)
            sig = I.with_room(sig, wet=0.30, decay=0.7, predelay=0.02,
                              seed=bar * 13 + bo, damp=3600)
            add(buf, sig, to_sr(t))
        bar += 4


def build_swells(buf, total, marks):
    """段前空气渐强（轻声，不用扫频 riser）"""
    for a, b, sid, layer, lead, drums in marks:
        if a <= 0 or layer < 2:
            continue
        add(buf, I.cymbal_swell(2.4, 0.032 + 0.006 * layer), to_sr(a - 2.0))
    n = to_sr(total)
    tail = I.rhodes(note(PROG[0].notes[0] - 12), 6.0, 0.11)
    add(buf, tail, to_sr(total - 5.0))


def build(total_sec, narration_marks=None):
    """合成整轨伴奏，返回立体声 (L, R)"""
    marks, _ = _layer_table()
    n = to_sr(total_sec + 2.0)
    buf = np.zeros(n, dtype=np.float32)

    if narration_marks is None:
        def narration_marks(a, b):
            return 0.0

    build_lowpad(buf, total_sec, marks)
    build_pad(buf, total_sec, marks, None)
    build_bass(buf, total_sec, marks)
    build_arp(buf, total_sec, marks, narration_marks)
    build_melody(buf, total_sec, marks, narration_marks)
    build_drums(buf, total_sec, marks)
    build_swells(buf, total_sec, marks)

    buf = I.with_room(buf, wet=0.14, decay=0.8, predelay=0.03, seed=99, damp=4200)
    # —— 母带柔化：v4 的核心，从根上削掉「刺」——
    buf = peak_eq(buf, 2600, 1.0, -2.5)       # 齿音/刺耳区下压
    buf = high_shelf(buf, 4500, -4.0)         # 4.5k 以上搁架衰减
    buf = lowpass(buf, 6800, 2)               # 6.8k 低通，去掉金属毛刺
    buf = peak_eq(buf, 180, 0.8, 2.0)         # 补一点木质
    buf = peak_eq(buf, 90, 0.7, 1.5)          # 补低频「底盘」
    buf = tape_sat(buf, 1.25, 0.30)
    buf = soft_sat(buf, 1.02)

    fi, fo = to_sr(3.0), to_sr(5.0)
    buf[:fi] *= np.linspace(0, 1, fi, dtype=np.float32) ** 1.2
    buf[-fo:] *= np.linspace(1, 0, fo, dtype=np.float32) ** 1.2

    right = np.copy(buf)
    delay = int(0.009 * SR)
    right[delay:] = right[:-delay]
    left = np.concatenate([np.zeros(int(0.004 * SR), dtype=np.float32),
                           buf[:-int(0.004 * SR)]])
    l, r = stereo_widen(left, right, amount=0.15)
    return l, r


def write_wav(path, l, r):
    import wave
    l = normalize(dither(l, 16, 1), 0.84)
    r = normalize(dither(r, 16, 2), 0.84)
    n = min(len(l), len(r))
    inter = np.empty(n * 2, dtype=np.int16)
    inter[0::2] = np.clip(l[:n], -1, 1) * 32767
    inter[1::2] = np.clip(r[:n], -1, 1) * 32767
    w = wave.open(path, "w")
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(inter.tobytes())
    w.close()


def _marks_from_timeline(root):
    import json, subprocess
    tj = os.path.join(root, "timeline.json")
    if not os.path.exists(tj):
        return None
    scenes = json.load(open(tj))["scenes"]
    spans = []
    for s in scenes.values():
        if not s.get("voice"):
            continue
        wav = os.path.join(root, "narration", "%s.wav" % s["voice"])
        if not os.path.exists(wav):
            continue
        d = float(subprocess.check_output(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "csv=p=0", wav]).strip())
        spans.append((s["voice_at"], s["voice_at"] + d))

    def marks(a, b):
        ov = 0.0
        for s, e in spans:
            ov = max(ov, min(b, e) - max(a, s))
        return max(0.0, ov / max(1e-6, b - a))
    return marks


if __name__ == "__main__":
    total = float(sys.argv[1]) if len(sys.argv) > 1 else 196.5
    out = sys.argv[2] if len(sys.argv) > 2 else "/workspace/.video/audio/bgm.wav"
    root = os.path.dirname(os.path.dirname(os.path.abspath(out)))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    t0 = time.time()
    l, r = build(total, _marks_from_timeline(root))
    write_wav(out, l, r)
    print("bgm.wav %.1fs 生成于 %.1fs -> %s" % (total, time.time() - t0, out))
