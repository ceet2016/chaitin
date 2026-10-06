# -*- coding: utf-8 -*-
"""乐器合成：高音木琴、电钢、温柔弦垫、贝斯、鼓组

设计目标：每一个音色都要有「起音噪声 + 非谐分音 + 逐分音衰减 + 空间混响」，
缺一个就会从「乐器」退化成「蜂鸣器」——这正是上一版 BGM 难听的根因。
"""
import numpy as np
from dsp import SR, lowpass, highpass, bandpass, tape_sat, reverb, fade, to_sr

_rng = np.random.default_rng(20261006)


def _partials(freq, dur, spec, amp_extra=1.0):
    """按 (倍频比, 振幅, 衰减率, 轻微失调) 叠加分音"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for ratio, amp, dec, detune in spec:
        f = freq * ratio * (1 + detune)
        if f >= SR * 0.46:
            continue
        out += amp * np.sin(2 * np.pi * f * t + _rng.uniform(0, 6.28))
    return out * amp_extra


# ---------- 高音木琴（二次元配器主力）----------
_MALLET_SPEC = [
    (1.00, 1.00, 9.0, 0.0000),
    (2.01, 0.42, 12.0, 0.0012),   # 钟琴类，2 倍频略高
    (3.02, 0.20, 15.0, -0.0015),
    (4.09, 0.10, 18.0, 0.0021),
    (5.42, 0.05, 22.0, -0.0026),
    (6.80, 0.03, 26.0, 0.0033),
    (8.20, 0.015, 30.0, -0.0040),
]


def mallet(freq, dur=1.1, amp=0.3):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    body = _partials(freq, dur, _MALLET_SPEC)
    body *= np.exp(-_MALLET_SPEC[0][2] * t) if False else 1.0
    # 逐分音衰减已含在 spec 里，这里只做统一尾巴
    body = body.astype(np.float32)
    # 起音敲击噪声：25ms 带通噪声，这是「木琴」和「正弦」的分水岭
    att = int(0.022 * SR)
    hammer = _rng.normal(0, 1, att).astype(np.float32)
    hammer = bandpass(hammer, 1500, 9000) * np.exp(
        -np.arange(att, dtype=np.float32) / SR * 220) * 0.34
    out = np.zeros(n, dtype=np.float32)
    out[:att] += hammer
    out += body
    out = highpass(out, 180)
    out = np.tanh(out * 1.15).astype(np.float32)
    env = fade(np.ones(n, dtype=np.float32), 0.001, min(0.25, dur * 0.4))
    return (out * env * amp).astype(np.float32)


# ---------- 电钢琴 ----------
_EP_SPEC = [
    (1.00, 1.00, 2.2, 0.0),
    (2.00, 0.36, 3.0, 0.0009),
    (3.01, 0.13, 4.4, -0.0011),
    (4.00, 0.07, 6.0, 0.0014),
    (5.00, 0.03, 8.0, -0.0016),
]


def e_piano(freq, dur=2.0, amp=0.28):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for ratio, a, dec, det in _EP_SPEC:
        f = freq * ratio * (1 + det)
        if f >= SR * 0.46:
            continue
        out += a * np.sin(2 * np.pi * f * t + _rng.uniform(0, 6.28)) * np.exp(-dec * t)
    # 一点 FM 味，让音头有金属光泽
    out += 0.09 * np.sin(2 * np.pi * freq * 2 * t +
                         1.6 * np.sin(2 * np.pi * freq * 3.02 * t)) * np.exp(-7.0 * t)
    out = tape_sat(out.astype(np.float32), 1.5, 0.35)
    env = np.exp(-1.1 * t).astype(np.float32) * 0.6 + 0.4
    env *= fade(np.ones(n, dtype=np.float32), 0.004, min(0.6, dur * 0.5))
    return (out * env * amp).astype(np.float32)


# ---------- 温柔弦垫 ----------
def strings(freqs, dur, amp=0.16, cutoff=2600):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for f in freqs:
        # 三层微失调模拟弦乐群
        for det, w in ((-0.0035, 0.9), (0.0, 1.0), (0.0038, 0.85)):
            vib = 1.0 + 0.0022 * np.sin(2 * np.pi * (4.3 + _rng.uniform(-0.4, 0.4)) * t)
            ph = _rng.uniform(0, 6.28)
            out += w * np.sin(2 * np.pi * f * (1 + det) * t * vib + ph)
            out += w * 0.30 * np.sin(2 * np.pi * f * 2 * (1 + det) * t + ph)
            out += w * 0.11 * np.sin(2 * np.pi * f * 3 * (1 + det) * t + ph)
    out /= max(1.0, len(freqs) * 3.05)
    out = lowpass(out, cutoff)
    # 缓慢起伏，避免长音发死
    out *= (0.82 + 0.18 * np.sin(2 * np.pi * 0.09 * t)).astype(np.float32)
    a, r = int(0.9 * SR), int(min(1.6, dur * 0.45) * SR)
    env = np.ones(n, dtype=np.float32)
    if a * 2 < n:
        env[:a] = np.linspace(0, 1, a, dtype=np.float32)
    if r * 2 < n:
        env[-r:] = np.linspace(1, 0, r, dtype=np.float32)
    return (out * env * amp).astype(np.float32)


# ---------- 贝斯 ----------
def bass(freq, dur, amp=0.30):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = (np.sin(2 * np.pi * freq * t) * 1.0
           + 0.26 * np.sin(2 * np.pi * freq * 2 * t)
           + 0.08 * np.sin(2 * np.pi * freq * 3 * t))
    out = tape_sat(out.astype(np.float32), 1.9, 0.5)
    out = lowpass(out, 900)
    env = np.exp(-1.4 * t).astype(np.float32) * 0.5 + 0.5
    env *= fade(np.ones(n, dtype=np.float32), 0.006, min(0.4, dur * 0.5))
    return (out * env * amp).astype(np.float32)


# ---------- 鼓组 ----------
def kick(dur=0.5, amp=0.34):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    # 音高走的不是指数直线，而是两段式，听感更「软」
    f = 52 + 95 * np.exp(-t * 26) + 22 * np.exp(-t * 6)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 5.2)
    click = bandpass(_rng.normal(0, 1, n).astype(np.float32), 900, 5000)
    click *= np.exp(-t * 130) * 0.16
    out = (body + click).astype(np.float32)
    return (np.tanh(out * 1.7) * amp).astype(np.float32)


def hat(dur=0.09, amp=0.075, open_=False):
    n = to_sr(dur if not open_ else max(dur, 0.42))
    t = np.arange(n, dtype=np.float32) / SR
    nz = _rng.normal(0, 1, n).astype(np.float32)
    out = bandpass(nz, 6000, 14000, order=3)
    dec = 55 if not open_ else 9
    return (out * np.exp(-t * dec) * amp).astype(np.float32)


def shaker(dur=0.15, amp=0.055):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    nz = _rng.normal(0, 1, n).astype(np.float32)
    # 噪声本身是随机包络，这种「沙沙」声比 constant 振幅的 hat 自然
    env = np.exp(-t * 22) * (0.6 + 0.4 * _rng.normal(0, 1, n).astype(np.float32) ** 2)
    out = bandpass(nz, 3500, 11000) * env
    return (out * amp).astype(np.float32)


def rim(dur=0.12, amp=0.10):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = (0.6 * np.sin(2 * np.pi * 1850 * t) + 0.4 * np.sin(2 * np.pi * 2740 * t))
    out *= np.exp(-t * 45)
    nz = bandpass(_rng.normal(0, 1, n).astype(np.float32), 2500, 8000) * np.exp(-t * 90)
    return ((out + 0.5 * nz) * amp).astype(np.float32)


def riser(dur=2.0, amp=0.10):
    """上扬噪声，用在段落切换处，把情绪推上去"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    nz = _rng.normal(0, 1, n).astype(np.float32)
    # 用扫频带通近似
    out = np.zeros(n, dtype=np.float32)
    seg = max(1, n // 24)
    for i in range(24):
        lo = 300 * (1 + i * 0.55)
        hi = min(15000, lo * 2.6)
        s = slice(i * seg, min(n, (i + 1) * seg))
        if s.start >= n:
            break
        out[s] = bandpass(nz[s], lo, hi)
    out = out + 0.25 * np.sin(2 * np.pi * np.cumsum(
        np.linspace(300, 2200, n)) / SR).astype(np.float32)
    env = (t / dur) ** 2.2
    return (out * env * amp).astype(np.float32)


_TAIL = 2.6


def with_room(sig, wet=0.24, decay=0.5, predelay=0.018, seed=3):
    return reverb(sig, dur=_TAIL, decay=decay, predelay=predelay, seed=seed, wet=wet)
