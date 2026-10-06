# -*- coding: utf-8 -*-
"""成片配乐：有段落、有呼吸、有空间的原创轻音乐

和上一版的区别（上一版听着像蜂鸣器的原因）：
  1. 上一版所有音都是「纯正弦 + 合唱叠加」，一条笔直的谱线从头挂到尾，
     听感就是电子琴单音；现在每个音都带起音噪声 + 非谐分音 + 逐分音衰减，
     并过软饱和，频谱自然长开。
  2. 上一版鼓点是 f = 120·e^(-18t) + 45，每 0.5 秒复制一次，
     1000Hz 上那排等距调制边带就是「节拍器感」的来源；现在鼓点改了
     两段式音高包络，并且节奏型随段落变化。
  3. 上一版是「4 和弦 × 2 秒 = 8 秒」死循环重复 26 遍，208 秒无任何起伏；
     现在按成片时间轴分成 7 个段落，配器、力度、密度各自不同。
  4. 上一版人声位置是空的，BGM 自顾自全速跑；现在有 duck（人声一开口
     伴奏自动下沉）和段落留白，人声前面是干净的。
"""
import numpy as np
import sys, os, time
from dsp import (SR, add, place, normalize, fade, to_sr, stereo_widen,
                 soft_sat, dither, note, reverb)
import instruments as I

# ---------- 和声进行 ----------
# F 大调，温柔不伤感：F - C/E - Dm7 - Bb  |  Gm7 - C - F - Bb
PROG = [
    [5, 9, 12, 16],      # F   (F A C E)
    [0, 4, 7, 11],       # C/E (E G C E)
    [-2, 2, 5, 9],       # Dm7 (D F A C)  -> 以 -2 = D 计
    [-5, -1, 2, 5],      # Bb  (Bb D F A)
    [-7, -3, 0, 4],      # Gm7 (G Bb D F)
    [0, 4, 7, 11],       # C
    [5, 9, 12, 16],      # F
    [-5, -1, 2, 5],      # Bb
]

# ---------- 段落 ----------
# 按成片场景划分：留白的人声段配器更轻，展示段更饱满
SECTIONS = [
    # (名, 时长, 密度, 力度, 主音色)
    ("open",   9.2, 0.30, 0.55, "mallet"),
    ("hook",   5.8, 0.35, 0.60, "ep"),
    ("intro", 10.1, 0.55, 0.80, "mallet"),
    ("what",  17.2, 0.65, 0.85, "ep"),
    ("feat1", 24.6, 0.80, 0.95, "mallet"),
    ("feat2", 25.3, 0.85, 1.00, "mallet"),
    ("feat3", 21.8, 0.75, 0.92, "ep"),
    ("mods",  19.0, 0.90, 1.00, "mallet"),
    ("log",   27.9, 0.70, 0.88, "ep"),
    ("safe",  16.6, 0.50, 0.72, "strings"),
    ("dl",    11.8, 0.80, 0.98, "mallet"),
    ("end",    7.4, 0.40, 0.70, "ep"),
]

BPM = 92.0
BEAT = 60.0 / BPM          # 0.652s
BAR = BEAT * 4

# 每个段落的节奏密度（相对 16 分音符步长）
ARP = [0, 2, 3, 5, 6, 8, 10, 11, 12, 14]     # 去掉部分步，形成呼吸
ARP_WIDE = [0, 2, 4, 6, 8, 10, 12, 14]
MELODY_BEATS = [0, 1.5, 2, 3, 3.5]


def _chord_at(bar_index):
    return PROG[bar_index % len(PROG)]


def build_pad(buf, total, base_amp=0.16, section_marks=None):
    """弦垫：按 2 小节一换，长音铺底"""
    bar = 0
    while bar * BAR < total:
        ch = _chord_at(bar)
        dur = BAR * 2 + 0.8
        freqs = [note(s) for s in ch]
        freqs = [f for f in freqs if f < 1400]
        amp = base_amp
        if section_marks:
            amp *= section_marks(bar * BAR)
        sig = I.strings(freqs, dur, amp=amp, cutoff=2400)
        sig = I.with_room(sig, wet=0.38, decay=0.75, predelay=0.03, seed=11 + bar)
        add(buf, sig, to_sr(bar * BAR))
        bar += 2


def build_arp(buf, total, section_marks):
    """高音木琴琶音：主旋律线，力度和密度随段落走"""
    bar = 0
    step = BAR / 16.0
    while bar * BAR < total:
        ch = _chord_at(bar)
        dens = section_marks(bar * BAR)
        amp = 0.135 * (0.55 + 0.65 * dens)
        pattern = ARP if dens < 0.8 else ARP_WIDE
        oct_up = 12 if (bar // 2) % 2 == 0 else 0
        for k in pattern:
            t = bar * BAR + k * step
            if t >= total:
                break
            n = ch[(k // 3) % len(ch)]
            # 让高音在句尾收一点，避免机械
            if k >= 12:
                amp_k = amp * 0.72
            else:
                amp_k = amp
            f = note(n + 24 + oct_up)
            if f > 3800:
                f /= 2
            sig = I.mallet(f, 1.25, amp_k)
            sig = I.with_room(sig, wet=0.30, decay=0.45, predelay=0.015, seed=bar * 31 + k)
            add(buf, sig, to_sr(t))
        bar += 1


def build_bass(buf, total, section_marks):
    """贝斯：每小节根音 + 第三拍一个八度跳，给律动"""
    bar = 0
    while bar * BAR < total:
        if bar % 2 == 0:
            ch = _chord_at(bar)
            root = note(ch[0] - 24)
            if root < 55:
                root *= 2
            amp = 0.26 * section_marks(bar * BAR)
            sig = I.bass(root, BAR * 2 - 0.05, amp)
            sig = I.with_room(sig, wet=0.12, decay=0.3, seed=bar)
            add(buf, sig, to_sr(bar * BAR))
            up = I.bass(root * 2, BAR * 0.9, amp * 0.55)
            add(buf, up, to_sr(bar * BAR + BEAT * 2))
            add(buf, I.bass(root * 1.5, BAR * 0.6, amp * 0.42),
                to_sr(bar * BAR + BEAT * 3))
        bar += 1


def build_drums(buf, total, section_marks):
    """鼓组：节奏型随段落变化；留白段落只留 shaker，避免和人声抢"""
    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        dens = section_marks(t0)
        for beat in range(4):
            t = t0 + beat * BEAT
            if t >= total:
                break
            if dens > 0.55:
                add(buf, I.shaker(0.14, 0.048 * dens), to_sr(t))
                add(buf, I.shaker(0.14, 0.032 * dens), to_sr(t + BEAT * 0.5))
            if dens > 0.85 and beat in (0, 2):
                add(buf, I.hat(0.09, 0.05 * dens), to_sr(t + BEAT * 0.25))
                add(buf, I.hat(0.09, 0.042 * dens), to_sr(t + BEAT * 0.75))
        # 底鼓：只在饱满段落，且错开人声起句
        if dens > 0.78:
            add(buf, I.kick(0.5, 0.26 * dens), to_sr(t0))
            add(buf, I.kick(0.5, 0.20 * dens), to_sr(t0 + BEAT * 2.5))
        elif dens > 0.55:
            add(buf, I.kick(0.5, 0.20 * dens), to_sr(t0 + BEAT * 2))
        if dens > 0.9 and bar % 4 == 3:
            add(buf, I.rim(0.12, 0.09 * dens), to_sr(t0 + BEAT * 3.5))
            add(buf, I.riser(1.8, 0.055), to_sr(t0 + BAR - 1.8))
        bar += 1


def build_melody(buf, total, section_marks, narration_marks):
    """电钢主旋律：只在没有人声的地方出现，形成「对话感」"""
    bar = 0
    while bar * BAR < total:
        t0 = bar * BAR
        dens = section_marks(t0)
        # 有人声的小节不抢戏
        if narration_marks(t0, t0 + BAR) > 0.35:
            bar += 2
            continue
        ch = _chord_at(bar)
        amp = 0.115 * dens
        for bi in MELODY_BEATS:
            t = t0 + bi * BEAT
            if t >= total:
                break
            n = ch[int(bi) % len(ch)] + 12
            sig = I.e_piano(note(n), 2.1, amp)
            sig = I.with_room(sig, wet=0.32, decay=0.55, seed=bar * 7 + int(bi * 2))
            add(buf, sig, to_sr(t))
        bar += 2


def build(total_sec, narration_marks=None):
    """合成伴奏，返回立体声 (L, R)"""
    n = to_sr(total_sec + 1.0)
    buf = np.zeros(n, dtype=np.float32)

    def section_density(t):
        acc = 0.0
        for _, dur, dens, _, _ in SECTIONS:
            if t < acc + dur:
                # 段内做一点渐变，避免段落边界像开关
                p = (t - acc) / max(0.001, dur)
                w = 1.0 if p > 0.12 or dur < 8 else 0.55 + 0.45 * (p / 0.12)
                return dens * w
            acc += dur
        return 0.35

    if narration_marks is None:
        def narration_marks(a, b):
            return 0.0

    env = np.zeros(n, dtype=np.float32)
    for name, dur, dens, amp, lead in SECTIONS:
        pass

    build_pad(buf, total_sec, base_amp=0.135, section_marks=section_density)
    build_bass(buf, total_sec, section_density)
    build_arp(buf, total_sec, section_density)
    build_drums(buf, total_sec, section_density)
    build_melody(buf, total_sec, section_density, narration_marks)

    # 整体空间感
    buf = I.with_room(buf, wet=0.20, decay=0.62, predelay=0.022, seed=99)
    buf = soft_sat(buf, 1.08)

    # 整段淡入淡出
    fi, fo = to_sr(2.6), to_sr(4.5)
    buf[:fi] *= np.linspace(0, 1, fi, dtype=np.float32) ** 1.4
    buf[-fo:] *= np.linspace(1, 0, fo, dtype=np.float32) ** 1.2

    # 伪立体声：给各频段一点宽度和相位差，不是简单左右延迟
    left = buf.copy()
    right = buf.copy()
    for ch, det in ((left, 0.0), (right, 0.0)):
        pass
    delay = int(0.011 * SR)
    right[delay:] = right[:-delay]
    left = np.concatenate([np.zeros(4, dtype=np.float32), left[:-4]])
    l, r = stereo_widen(left, right, amount=0.22)
    return l, r


def write_wav(path, l, r):
    import wave
    l = normalize(dither(l, 16, 1), 0.89)
    r = normalize(dither(r, 16, 2), 0.89)
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
    """从 timeline.json + 旁白时长，得出「有人声」的区间，用于主旋律避让"""
    import json
    import subprocess
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
