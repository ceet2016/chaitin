# -*- coding: utf-8 -*-
"""成片配乐：原创轻音乐《为你的防线，站好每一班岗》

为什么重写（v2 版听感问题的根因）：

  v2 的和声进行写的是 F-C-Dm-Bb-Gm-C-F-Bb，但代码里每个和弦都**没有指定
  低音**，贝斯一律取 voicing 的最低音。于是实际听到的是
  F / E / D / Bb / G / E / F / Bb —— 其中 C/E、C 两小节其实都响在 E 上，
  低音线出现「E-E」重复和「F→E→D→Bb→G」的不规则跳进，和弦进行散架，
  听感就是「走调」。这次的 Chord 显式带 bass 字段，根音线可以单独设计。

  另外 v2 的每小节内容完全一样，27 秒的段落里 12 次原样重复；
  鼓点是 4/4 抬拍上的固定动圈（咚—哒 50~100 次），像节拍器；
  打击乐 + 高音木琴 + 电钢挤在 1.5~3k，频谱质心 2~2.5kHz，刺耳。

这次的做法：
  1. 和声：8 小节一循环，低音线逐音级进，每 4 小节换色彩和弦（Fmaj7/C → C7）
  2. 节奏：72 BPM，「咚—哒-哒」的双拍律动，加 ghost 军鼓与 16 分沙锤
  3. 配器：三角钢琴（主）+ 弦乐群（和声）+ 管钟（前奏/落点）+ 电贝斯 + 原声鼓
  4. 编排：8 个段落各有动机；前奏和「安全」段无鼓无贝斯，中段逐层加人
  5. 对比：整曲分成 4 个动态档（0 稀疏 → 3 全奏），段落边界才有「推力」
"""
import numpy as np
import sys, os, time

from dsp import (SR, add, place, normalize, fade, to_sr, stereo_widen, soft_sat,
                 dither, note, highpass, peak_eq, sidechain_gain, rms_env, smooth_env)
import instruments as I

# ---------- 和声 ----------
# Chord(root, notes, bass)：root 是根音半音数（0 = A4），bass 单独指定
class Chord:
    __slots__ = ("root", "notes", "bass", "name")

    def __init__(self, root, notes, bass, name):
        self.root, self.notes, self.bass, self.name = root, notes, bass, name


F, C, D, Bb, G, A, B = 5, 0, -2, -5, -7, -10, -9

# 8 小节一循环，低音线级进（F-G-A-Bb-C-C-F-Bb），第 4 小节换色彩和弦
PROG = [
    Chord(F,  [5, 9, 12, 16],      5,  "Fmaj7"),    # F  A  C  E
    Chord(C,  [5, 7, 11, 14],      2,  "Cadd9"),    # C  E  G  D  （低音 D）
    Chord(D,  [-2, 2, 5, 9],       0,  "Dm7"),      # D  F  A  C
    Chord(Bb, [-5, -1, 2, 5],      -3, "Bbadd9"),   # Bb D  F  G  （低音 G）
    Chord(C,  [4, 7, 11, 14],      -3, "C7"),       # C  E  G  Bb（低音 G）
    Chord(F,  [5, 9, 12, 16],      5,  "Fmaj7"),
    Chord(D,  [-2, 2, 5, 9],       -2, "Dm7"),
    Chord(Bb, [-5, -1, 2, 5],      -5, "Bb"),
]

BPM = 72.0
BEAT = 60.0 / BPM            # 0.8333s
BAR = BEAT * 4               # 3.3333s

# ---------- 段落 ----------
# (id, 时长, 层数 0..3, 主导动机, 是否有鼓)
SECTIONS = [
    ("open",    9.20,  0, "intro",  False),
    ("hook",    5.80,  1, "motif",  False),
    ("intro",  10.10,  1, "motif",  False),
    ("what",   17.20,  2, "motif",  True),
    ("feat1",  24.60,  2, "travel", True),
    ("feat2",  25.30,  2, "motif",  True),
    ("feat3",  21.80,  2, "travel", True),
    ("mods",   19.00,  3, "motif",  True),
    ("log",    27.90,  1, "lament", True),
    ("safe",   16.60,  0, "awe",    False),
    ("dl",     11.80,  3, "motif",  True),
    ("end",     7.40,  1, "coda",   False),
]


def chord_of(bar):
    return PROG[bar % len(PROG)]


def _layer_table():
    """段落累积起点，用于把任意绝对时间映射到 (层数, 主导动机)"""
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
    """段落头部几小节做淡入，避免段落之间像开关"""
    return min(1.0, (bar_in_section + 1) / float(ramp))


# ---------- 声部 ----------

def build_pad(buf, total, marks, lead_of_bar):
    """弦乐和声：两小节一换，力度由层数控制"""
    bar = 0
    while bar * BAR < total:
        sid, layer, lead, drums, inner = section_at(bar * BAR, marks)
        if layer == 0:
            box = [chord_of(bar).root + 12, chord_of(bar).root + 19]
            amp = 0.055 if sid == "open" else 0.07
            freqs = [note(s) for s in box]
        else:
            ch = chord_of(bar)
            freqs = [note(s + 12) for s in ch.notes if note(s + 12) < 3200]
            amp = 0.055 + 0.035 * layer
            if lead == "lament":
                amp *= 0.72
        dur = BAR * 2 + 1.0
        swell = 0.0 if bar else 1.6
        sig = I.strings(freqs, dur, amp=amp, cutoff=2600 + 300 * layer, swell=swell)
        sig = I.with_room(sig, wet=0.42, decay=0.95, predelay=0.035, seed=11 + bar,
                          damp=5200)
        add(buf, sig, to_sr(bar * BAR))
        bar += 2


def build_bass(buf, total, marks):
    """电贝斯：根音 + 五音/八度点，2、4 拍走动，给律动"""
    bar = 0
    while bar * BAR < total:
        sid, layer, lead, drums, inner = section_at(bar * BAR, marks)
        if layer == 0 and sid not in ("hook", "intro"):
            bar += 1
            continue
        ch = chord_of(bar)
        amp = 0.20 + 0.055 * layer
        if lead == "lament":
            amp *= 0.8
        root = ch.bass
        f_root = note(root - 24)
        if f_root < 44:
            f_root = note(root - 12)
        add(buf, I.bass(f_root, BAR * 0.92, amp * 0.95, finger=1.1), to_sr(bar * BAR))
        if layer >= 1:
            add(buf, I.bass(note(ch.notes[2] - 24 if ch.notes[2] - 24 > -30 else ch.notes[2] - 12),
                            BAR * 0.42, amp * 0.62), to_sr(bar * BAR + BEAT * 2))
        if layer >= 2:
            add(buf, I.bass_pick(note(root - 12), BAR * 0.38, amp * 0.5),
                to_sr(bar * BAR + BEAT * 2.5))
            add(buf, I.bass(note(root - 24) if lead == "motif" else note(root - 17),
                            BAR * 0.34, amp * 0.44), to_sr(bar * BAR + BEAT * 3.5))
        bar += 1


def build_drums(buf, total, marks):
    """原声鼓组：4/4「咚—哒-哒」，ghost 军鼓与 16 分沙锤随层数加入"""
    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        sid, layer, lead, drums, inner = section_at(t0, marks)
        if not drums or layer == 0:
            bar += 1
            continue
        ramp = _phrase_amp(bar % 4, 3)
        amp = (0.72 + 0.16 * layer) * ramp
        # 底鼓：1、3 拍正拍 + 2.5 拍的推
        add(buf, I.kick(0.55, 0.30 * amp), to_sr(t0))
        add(buf, I.kick(0.55, 0.24 * amp), to_sr(t0 + BEAT * 2))
        if layer >= 2:
            add(buf, I.kick(0.5, 0.17 * amp), to_sr(t0 + BEAT * 2.5))
        # 军鼓：2、4 拍，层数越高越实
        for b in (1, 3):
            a = (0.16 if layer >= 2 else 0.10) * amp
            add(buf, I.snare(0.3, a), to_sr(t0 + b * BEAT))
            add(buf, I.snare(0.12, a * 0.28, ghost=True), to_sr(t0 + b * BEAT + BEAT * 0.5))
        if layer >= 2:
            add(buf, I.snare(0.14, 0.055 * amp, ghost=True), to_sr(t0 + BEAT * 0.75))
        # 沙锤：8 分，层数 3 加 16 分
        add(buf, I.shaker(0.13, 0.032 * amp), to_sr(t0 + BEAT * 0.5))
        add(buf, I.shaker(0.13, 0.026 * amp), to_sr(t0 + BEAT * 1.5))
        add(buf, I.shaker(0.13, 0.030 * amp), to_sr(t0 + BEAT * 2.5))
        add(buf, I.shaker(0.13, 0.024 * amp), to_sr(t0 + BEAT * 3.5))
        if layer >= 3:
            for b in (0, 1, 2, 3):
                add(buf, I.shaker16(0.07, 0.016 * amp), to_sr(t0 + b * BEAT + BEAT * 0.25))
                add(buf, I.shaker16(0.07, 0.014 * amp), to_sr(t0 + b * BEAT + BEAT * 0.75))
        # 段落末小加花：开镲
        if inner > 0.88:
            add(buf, I.hat(0.3, 0.05 * amp, open_=True), to_sr(t0 + BEAT * 3.5))
        # 镲片渐强：进入层数 3 / 段落切换
        if abs(inner) < 0.02 and layer >= 2:
            add(buf, I.cymbal_swell(1.9, 0.06), to_sr(t0 - 1.4))
        bar += 1


def build_arp(buf, total, marks, narration_marks):
    """分解和弦：从低到高再回落，密度随层数；主旋律让位给人声"""
    eigths = [0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5]
    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        sid, layer, lead, drums, inner = section_at(t0, marks)
        ch = chord_of(bar)
        # 层数 -> 分解和弦音型（上行 / 弧线 / 八音循环）
        SHAPES = {0: [], 1: [[0, 1, 2]], 2: [[0, 2, 3], [0, 1, 2, 3, 2, 1, 2, 3]],
                  3: [[0, 1, 2, 3, 2, 1, 2, 3]]}
        shape = SHAPES[layer][bar % len(SHAPES[layer])] if layer else []
        amp = (0.075 + 0.02 * layer) * _phrase_amp(bar % 4, 3)
        if narration_marks(t0, t0 + BAR) > 0.6:
            amp *= 0.55           # 人声满句时，钢琴退到后面
        for i, k in enumerate(shape):
            t = t0 + eigths[i] * BEAT * 2 if i < len(eigths) else t0
            if t >= total:
                break
            n = ch.notes[k % len(ch.notes)] + (12 if k % 4 < 2 else 24)
            jitter = 0.012 * np.sin(bar * 2.7 + i)   # 人的手不可能每次都精准
            sig = I.piano(note(n), 1.9, amp * (1.0 if i % 2 == 0 else 0.78))
            sig = I.with_room(sig, wet=0.26, decay=0.5, predelay=0.012,
                              seed=bar * 31 + i, damp=4600)
            add(buf, sig, to_sr(t + jitter))
        bar += 1


def build_melody(buf, total, marks, narration_marks):
    """主旋律：钢琴 + 管钟/钟琴点落点，只在人声留白处出现"""
    # (小节偏移, 拍偏移, 音级索引, 时值, 力度, 是否管钟)
    MOTIF = [(0, 0.0, 2, 1.2, 1.00, False), (0, 1.5, 3, 0.6, 0.66, False),
             (1, 0.0, 4, 1.6, 0.92, False), (1, 2.0, 5, 1.0, 0.62, True),
             (2, 0.0, 4, 1.2, 0.86, False), (2, 1.5, 3, 0.6, 0.60, False),
             (3, 0.0, 2, 2.4, 1.00, False), (3, 2.5, 1, 0.8, 0.55, False)]
    TRAVEL = [(0, 0.0, 4, 1.0, 0.95, False), (0, 1.0, 5, 0.5, 0.62, False),
              (1, 0.0, 5, 1.2, 0.85, False), (1, 2.0, 4, 0.8, 0.58, False),
              (2, 0.0, 3, 1.4, 0.88, False), (2, 2.0, 3, 0.8, 0.60, False),
              (3, 0.0, 2, 2.0, 0.92, True), (3, 2.0, 3, 1.0, 0.55, False)]
    LAMENT = [(0, 0.0, 2, 1.6, 0.80, False), (1, 0.0, 3, 1.0, 0.60, False),
              (2, 0.0, 4, 2.0, 0.78, False), (3, 0.0, 3, 1.4, 0.60, False),
              (3, 2.0, 2, 1.6, 0.55, False)]
    AWE = [(0, 0.0, 2, 3.0, 0.72, True), (2, 0.0, 4, 3.0, 0.60, False)]
    CODA = [(0, 0.0, 2, 2.0, 0.85, True), (1, 0.0, 3, 1.2, 0.62, False),
            (2, 0.0, 4, 2.6, 0.80, False), (3, 0.0, 2, 3.2, 0.72, True)]
    INTRO = [(0, 0.0, 2, 2.4, 0.72, True), (1, 2.0, 3, 1.2, 0.52, False),
             (2, 0.0, 4, 2.4, 0.68, False), (3, 1.0, 2, 2.0, 0.58, True)]
    PATTERNS = dict(motif=MOTIF, travel=TRAVEL, lament=LAMENT, awe=AWE,
                    coda=CODA, intro=INTRO)

    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        sid, layer, lead, drums, inner = section_at(t0, marks)
        if narration_marks(t0, t0 + BAR * 4) > 0.45:
            bar += 4          # 有人说话的 4 小节整块跳过，绝不抢戏
            continue
        pat = PATTERNS.get(lead, MOTIF)
        for bo, beat, deg, dur, amp, bell in pat:
            t = t0 + bo * BAR + beat * BEAT
            if t >= total:
                continue
            ch = chord_of(bar + bo)
            n = ch.notes[deg % len(ch.notes)] + (12 if deg < 4 else 24)
            a = amp * (0.85 + 0.15 * layer)
            if bell and layer <= 1:
                sig = I.tubular_bell(note(n - 12), 5.0, 0.135 * a)
            elif bell:
                sig = I.glock(note(n), 1.3, 0.055 * a)
            else:
                sig = I.piano(note(n), 2.6, 0.15 * a)
            sig = I.with_room(sig, wet=0.30, decay=0.7, predelay=0.02,
                              seed=bar * 13 + bo, damp=5000)
            add(buf, sig, to_sr(t))
        bar += 4


def build_swells(buf, total, marks):
    """段前镲片与低频抬升，做连接（替代 v2 那种扫频 riser）"""
    for a, b, sid, layer, lead, drums in marks:
        if a <= 0 or layer < 2:
            continue
        add(buf, I.cymbal_swell(2.4, 0.055 + 0.01 * layer), to_sr(a - 2.0))
    # 全体收尾：末小节留给管钟与弦乐
    n = to_sr(total)
    tail = I.tubular_bell(note(PROG[0].notes[0] - 12), 7.0, 0.16)
    add(buf, tail, to_sr(total - 5.0))


def build(total_sec, narration_marks=None):
    """合成整轨伴奏，返回立体声 (L, R)"""
    marks, _ = _layer_table()
    n = to_sr(total_sec + 2.0)
    buf = np.zeros(n, dtype=np.float32)

    if narration_marks is None:
        def narration_marks(a, b):
            return 0.0

    build_pad(buf, total_sec, marks, None)
    build_bass(buf, total_sec, marks)
    build_arp(buf, total_sec, marks, narration_marks)
    build_melody(buf, total_sec, marks, narration_marks)
    build_drums(buf, total_sec, marks)
    build_swells(buf, total_sec, marks)

    # 空间：整轨一次轻混响，把钢琴/弦乐放进同一个房间
    buf = I.with_room(buf, wet=0.16, decay=0.75, predelay=0.028, seed=99, damp=6000)
    # 削掉高音木琴时代的 3k 刺，提一点 120Hz 的木质
    buf = peak_eq(buf, 3200, 1.0, -2.0)
    buf = peak_eq(buf, 140, 0.8, 1.5)
    buf = soft_sat(buf, 1.05)

    fi, fo = to_sr(3.0), to_sr(5.0)
    buf[:fi] *= np.linspace(0, 1, fi, dtype=np.float32) ** 1.4
    buf[-fo:] *= np.linspace(1, 0, fo, dtype=np.float32) ** 1.2

    # 立体声：钢琴/弦乐铺宽，贝斯与底鼓保持中置
    right = np.copy(buf)
    delay = int(0.009 * SR)
    right[delay:] = right[:-delay]
    left = np.concatenate([np.zeros(int(0.004 * SR), dtype=np.float32), buf[:-int(0.004 * SR)]])
    l, r = stereo_widen(left, right, amount=0.18)
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
    """从 timeline.json + 旁白时长，得出「有人声」的区间，用于旋律避让"""
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
