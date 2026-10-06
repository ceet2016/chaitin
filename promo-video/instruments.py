# -*- coding: utf-8 -*-
"""乐器合成：三角钢琴、弦乐群、管钟、电贝斯、原声鼓组、打点沙锤

设计原则：真实乐器的「像」来自三件事——起音的噪声瞬态、泛音的**非整数倍**
比例、以及每个分音各自不同的衰减率。三者缺一，听起来就是蜂鸣器。

和上一版的区别：
  * 音高不是 (1,2,3,4..) 这种整数倍，而是按各自的非谐性系数排布，
    这样频谱上是一族「不重叠」的峰，叠加时才不会糊成一条线
  * 每个音都过一遍指向性高通 + 轻微饱和，去掉 60~200Hz 的泥
  * 所有短音都有 20~40ms 的尾，长音有延迟颤音（人耳判断「真乐器」的关键）
"""
import numpy as np
from dsp import (SR, lowpass, highpass, bandpass, tape_sat, soft_sat, peak_eq,
                 reverb, fade, to_sr)

_rng = np.random.default_rng(20261007)


def _noise(n, seed=None):
    r = _rng if seed is None else np.random.default_rng(seed)
    return r.normal(0, 1, n).astype(np.float32)


def _ens(y, n, atk=0.004, rel=0.2):
    """起音/收尾包络"""
    y = y.astype(np.float32)
    a, r = to_sr(atk), to_sr(rel)
    if 0 < a < n:
        y[:a] *= np.linspace(0, 1, a, dtype=np.float32)
    if 0 < r < n:
        y[-r:] *= np.linspace(1, 0, r, dtype=np.float32)
    return y


# ---------- 三角钢琴 ----------
# (倍频比, 振幅, 衰减率1/s, 微失调)，非谐性 0.0004 是钢琴特有的「键感」
_PIANO_SPEC = [
    (1.0000, 1.00, 1.05, 0.0000),
    (2.0008, 0.46, 1.65, 0.0006),
    (3.0030, 0.24, 2.30, -0.0009),
    (4.0070, 0.13, 3.00, 0.0012),
    (5.0120, 0.075, 3.90, -0.0015),
    (6.0190, 0.045, 4.90, 0.0019),
    (7.0280, 0.028, 6.10, -0.0023),
    (8.0390, 0.017, 7.60, 0.0028),
    (9.0520, 0.010, 9.40, -0.0033),
]


def piano(freq, dur=1.6, amp=0.3, bright=1.0, detune=0.0):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for ratio, a, dec, det in _PIANO_SPEC:
        f = freq * ratio * (1 + det + detune)
        if f >= SR * 0.45:
            continue
        out += a * np.sin(2 * np.pi * f * t + _rng.uniform(0, 6.28)) * np.exp(-dec * t)
    # 琴槌噪声：450Hz~9kHz 的短促带通噪声，钢琴的「击」感就来自它
    att = to_sr(0.007)
    ham = bandpass(_noise(att), 450, min(9000, freq * 14 + 2500))
    ham *= np.exp(-np.arange(att, dtype=np.float32) / SR * 900) * 0.5
    out[:att] += ham
    # 弦的拍频：两根弦略微不齐，长音会缓慢「涌动」
    out += 0.12 * np.sin(2 * np.pi * freq * 2.0008 * (1 + 0.0012) * t) * np.exp(-0.9 * t)
    out = highpass(out, 38)
    out = peak_eq(out, min(4200, freq * 5.5), 1.1, 2.2 * bright)
    out = tape_sat(out, 1.35, 0.3)
    return _ens(out * amp, n, 0.0018, min(0.35, dur * 0.35)).astype(np.float32)


# ---------- Rhodes 电钢（温暖、软起音）----------
# 与三角钢琴的区别：分音更少、高次分音砍掉、起音是「鼓包」而不是「锤击」，
# 再加一点颤音（tine 的典型特征），整体落在中低音区，作为铺底不抢耳。
_RHODES_SPEC = [
    (1.000, 1.00, 1.30, 0.0000),
    (2.003, 0.34, 2.10, 0.0007),
    (3.008, 0.13, 3.40, -0.0011),
    (4.014, 0.055, 5.20, 0.0014),
]


def rhodes(freq, dur=2.0, amp=0.18, trem=0.0):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for ratio, a, dec, det in _RHODES_SPEC:
        f = freq * ratio * (1 + det)
        if f >= SR * 0.45:
            continue
        out += a * np.sin(2 * np.pi * f * t + _rng.uniform(0, 6.28)) * np.exp(-dec * t)
    # tine 颤音：电钢那种轻微的「嗡」，很轻
    out += 0.10 * np.sin(2 * np.pi * freq * 2.003 * 1.0015 * t) * np.exp(-1.4 * t)
    # 软起音（30ms 鼓包）而不是钢琴的 7ms 锤击，这是「棉」的来源
    atk = to_sr(0.030)
    env = np.ones(n, dtype=np.float32)
    env[:atk] = np.linspace(0, 1, atk, dtype=np.float32) ** 1.6
    out *= env
    out = highpass(out, 55)
    out = lowpass(out, 3400, 2)
    out = tape_sat(out, 1.3, 0.28)
    return _ens(out * amp, n, 0.004, min(0.5, dur * 0.4)).astype(np.float32)


# ---------- 弦乐群 ----------
_STR_SPEC = [
    (1.000, 1.00, 0.075),
    (2.001, 0.42, 0.11),
    (3.004, 0.20, 0.16),
    (4.009, 0.10, 0.22),
    (5.016, 0.05, 0.30),
    (6.026, 0.025, 0.40),
]


def strings(freqs, dur, amp=0.16, cutoff=3000, swell=0.0):
    """弦乐群：每个音位用 5 把琴微失调叠出「群感」，带延迟颤音"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    voices = ((-0.0042, 0.80), (-0.0016, 0.95), (0.0, 1.0), (0.0018, 0.92), (0.0045, 0.78))
    for f0 in freqs:
        for det, w in voices:
            f = f0 * (1 + det)
            # 延迟颤音：起音稳，1.2 秒后才开始起伏
            vib = 1.0 + 0.0032 * np.sin(2 * np.pi * (4.6 + _rng.uniform(-0.5, 0.5)) * t) \
                * (1 - np.exp(-t / 1.2))
            ph = _rng.uniform(0, 6.28)
            for ratio, a, dec in _STR_SPEC:
                if f * ratio >= SR * 0.45:
                    continue
                out += w * a * np.sin(2 * np.pi * f * ratio * t * vib + ph) * np.exp(-dec * t * 0.12)
    out /= max(1.0, len(freqs) * 5.0 * 0.9)
    out = lowpass(out, cutoff, 2)
    # 弓毛噪声：极轻的高频噪声层，让「群」有质感
    bow = lowpass(bandpass(_noise(n), 900, 6000), 4000) * 0.035
    bow *= np.clip(t / max(0.001, dur * 0.3), 0, 1)
    out += bow
    a = 0.45 if swell <= 0 else max(0.45, dur * 0.55)
    r = min(1.8, dur * 0.5)
    env = np.ones(n, dtype=np.float32)
    if swell > 0:
        env[:to_sr(swell)] = np.linspace(0, 1, to_sr(swell), dtype=np.float32) ** 1.3
    return _ens(out * amp, n, a, r).astype(np.float32)


def tremolo_strings(freqs, dur, amp=0.1, rate=7.5):
    """震音弦乐：紧张段落的推力"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    base = strings(freqs, dur, amp=1.0, cutoff=2600)
    trem = 0.55 + 0.45 * np.abs(np.sin(2 * np.pi * rate * t))
    trem *= np.clip(t / 0.25, 0, 1)
    return (base * trem * amp).astype(np.float32)


# ---------- 管钟（有钟声的钟，起音有铜锣的杂音）----------
def _sine_with_phase_correct(harm_ratios, freqs, amps, decays, n, seed):
    """Strickland 方法：只用正弦，但给每个分音配正确初相位，避免起音爆点"""
    r = np.random.default_rng(seed)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for f0, ratio, a, dec in zip(freqs, harm_ratios, amps, decays):
        f = f0 * ratio
        if f >= SR * 0.45:
            continue
        phase = r.uniform(0, 2 * np.pi)
        if dec > 1e-6:
            out += (a * np.sin(2 * np.pi * f * t + phase)
                    * np.exp(-dec * t) * np.exp(-f * 0.0))
        else:
            out += a * np.sin(2 * np.pi * f * t + phase)
    return out.astype(np.float32)


def tubular_bell(freq, dur=6.0, amp=0.3, strike=1.0):
    """管钟：不谐和分音族 (0.56 0.92 1.19 1.71 2 2.74 3 3.76 4.07) + 金属起音"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    ratios = [0.56, 0.92, 1.19, 1.71, 2.00, 2.74, 3.00, 3.76, 4.07]
    amps = [0.55, 1.00, 0.45, 0.55, 0.42, 0.30, 0.22, 0.14, 0.09]
    decs = [0.30, 0.42, 0.55, 0.90, 1.30, 1.90, 2.50, 3.20, 4.00]
    out = _sine_with_phase_correct(ratios, [freq] * len(ratios), amps, decs, n, 7)
    body = np.zeros(n, dtype=np.float32)
    atk = to_sr(0.02)
    body[:atk] = highpass(_noise(atk), 2500) * np.exp(
        -np.arange(atk, dtype=np.float32) / SR * 120) * 0.5 * strike
    out = out + body
    # 钢管壳体噪声：低频的「嗡」铺底
    shell = bandpass(_noise(n), 180, 700) * np.exp(-t * 1.1) * 0.16
    out += shell
    out = highpass(out, 60)
    out = peak_eq(out, 3200, 0.9, -2.5)      # 削掉 3k 的「硬」色
    return _ens(out * amp, n, 0.0015, min(1.6, dur * 0.45)).astype(np.float32)


def music_box(freq, dur=1.2, amp=0.2):
    """八音盒：高频泛音 + 快衰减，用在抒情段的高音点"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for ratio, a, dec in ((1.0, 1.0, 3.2), (2.01, 0.5, 5.0), (3.05, 0.26, 7.5),
                          (5.4, 0.12, 11.0), (6.9, 0.06, 15.0)):
        f = freq * ratio
        if f < SR * 0.45:
            out += a * np.sin(2 * np.pi * f * t + _rng.uniform(0, 6.28)) * np.exp(-dec * t)
    out = highpass(out, 300)
    return _ens(out * amp, n, 0.001, 0.2).astype(np.float32)


# ---------- 电贝斯（指弹）----------
_BASS_SPEC = [
    (1.000, 1.00, 0.55),
    (2.005, 0.34, 0.85),
    (3.012, 0.16, 1.30),
    (4.024, 0.07, 1.90),
    (5.040, 0.035, 2.60),
]


def bass(freq, dur, amp=0.3, finger=0.9, glide=0.0):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for ratio, a, dec in _BASS_SPEC:
        f = freq * ratio
        if f >= 6000:
            continue
        out += a * np.sin(2 * np.pi * f * t + _rng.uniform(0, 6.28)) * np.exp(-dec * t)
    # 拨弦噪声 + 品格细节
    att = to_sr(0.012)
    pf = bandpass(_noise(att), 1200, 5200) * np.exp(
        -np.arange(att, dtype=np.float32) / SR * 320) * 0.28 * finger
    out[:att] += pf
    out = lowpass(out, 2000)
    out = peak_eq(out, 110, 0.9, 2.0)
    out = tape_sat(out, 1.7, 0.42)
    env = np.exp(-0.35 * t) * 0.55 + 0.45
    return _ens(out * env * amp, n, 0.005, min(0.12, dur * 0.2)).astype(np.float32)


def bass_pick(freq, dur, amp=0.28):
    """指尖勾弦：比指弹更亮，用于推进段落"""
    y = bass(freq, dur, amp, finger=1.5)
    return highpass(y, 55).astype(np.float32)


# ---------- 打击乐 ----------
def kick(dur=0.55, amp=0.34):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    f = 48 + 70 * np.exp(-t * 22) + 16 * np.exp(-t * 5)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 4.6)
    click = bandpass(_noise(n), 1400, 6000) * np.exp(-t * 180) * 0.12
    return (soft_sat((body + click) * amp * 1.1, 1.4)).astype(np.float32)


def snare(dur=0.32, amp=0.16, ghost=False):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    tone = (np.sin(2 * np.pi * 186 * t) * 0.5 + np.sin(2 * np.pi * 278 * t) * 0.3) \
        * np.exp(-t * 28)
    rattle = bandpass(_noise(n), 1200, 6500, 3) * np.exp(-t * (70 if ghost else 34))
    out = tone * 0.5 + rattle * (0.35 if ghost else 1.0)
    return _ens(out * amp, n, 0.001, 0.05).astype(np.float32)


def hat(dur=0.1, amp=0.06, open_=False):
    n = to_sr(dur if not open_ else max(dur, 0.5))
    t = np.arange(n, dtype=np.float32) / SR
    nz = bandpass(_noise(n), 5000, 11000, 2)
    return (nz * np.exp(-t * (48 if not open_ else 7.5)) * amp).astype(np.float32)


def shaker(dur=0.13, amp=0.045):
    """沙锤：两次噪声峰（去+回），比恒定振幅的自然"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    env = np.exp(-t * 26) * (1 + 0.6 * np.exp(-t * 90))
    nz = bandpass(_noise(n), 3000, 9000) * env
    return (nz * amp).astype(np.float32)


def shaker16(dur=0.07, amp=0.03):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    nz = bandpass(_noise(n), 4200, 11000) * np.exp(-t * 55)
    return (nz * amp).astype(np.float32)


def cymbal_swell(dur=2.6, amp=0.1):
    """镲片渐强：段落切换处的「呼气」，不是老的扫频 riser"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    nz = bandpass(_noise(n), 2200, 9000, 2)
    env = (t / dur) ** 2.6
    # 叠一点中频正弦，避免纯噪声听起来像吹气
    tone = np.sin(2 * np.pi * np.cumsum(np.linspace(2200, 4200, n)) / SR) * 0.10
    return (lowpass(nz, 9000) * env * amp + lowpass(tone, 6000) * env * amp * 0.8).astype(np.float32)


def rim(dur=0.12, amp=0.1):
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = (0.6 * np.sin(2 * np.pi * 1850 * t) + 0.4 * np.sin(2 * np.pi * 2740 * t))
    out *= np.exp(-t * 45)
    nz = bandpass(_noise(n), 2500, 8000) * np.exp(-t * 90)
    return ((out + 0.5 * nz) * amp).astype(np.float32)


def glock(freq, dur=1.4, amp=0.12):
    """钟琴：段落落点的高音亮点"""
    n = to_sr(dur)
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros(n, dtype=np.float32)
    for ratio, a, dec in ((1.0, 1.0, 2.4), (2.76, 0.55, 4.0), (5.40, 0.28, 6.5),
                          (8.93, 0.14, 10.0)):
        f = freq * ratio
        if f < SR * 0.45:
            out += a * np.sin(2 * np.pi * f * t + _rng.uniform(0, 6.28)) * np.exp(-dec * t)
    out[:to_sr(0.006)] += highpass(_noise(to_sr(0.006)), 4000) * 0.4
    return (highpass(out, 500) * amp).astype(np.float32)


_TAIL = 2.4


def with_room(sig, wet=0.24, decay=0.5, predelay=0.018, seed=3, damp=6500):
    return reverb(sig, dur=_TAIL, decay=decay, predelay=predelay, seed=seed,
                  wet=wet, damp=damp)
