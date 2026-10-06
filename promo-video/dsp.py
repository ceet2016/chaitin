# -*- coding: utf-8 -*-
"""基础 DSP 积木：滤波、混响、软饱和、抖动

纯 numpy/scipy 实现，不依赖任何外部素材，保证 CI 可复现。
"""
import numpy as np
from scipy.signal import lfilter, butter, fftconvolve

SR = 48000


def to_sr(dur_sec, sr=SR):
    return int(round(dur_sec * sr))


# ---------- 滤波 ----------

def lowpass(x, cutoff, order=2, sr=SR):
    b, a = butter(order, cutoff / (sr / 2), btype="low")
    return lfilter(b, a, x).astype(np.float32)


def highpass(x, cutoff, order=2, sr=SR):
    b, a = butter(order, cutoff / (sr / 2), btype="high")
    return lfilter(b, a, x).astype(np.float32)


def bandpass(x, lo, hi, order=2, sr=SR):
    b, a = butter(order, [lo / (sr / 2), hi / (sr / 2)], btype="band")
    return lfilter(b, a, x).astype(np.float32)


# ---------- 非线性 ----------

def soft_sat(x, drive=1.2):
    """tanh 软饱和：加偶次谐波，听感更「实」，也是去掉纯正弦味的关键一步"""
    return np.tanh(x * drive).astype(np.float32)


def tape_sat(x, drive=1.6, mix=0.45):
    """带二次谐波的磁带饱和，让单音不再像蜂鸣器"""
    d = np.tanh(x * drive).astype(np.float32)
    d2 = np.tanh(x * drive * 0.55).astype(np.float32)
    return ((1 - mix) * x + mix * (0.72 * d + 0.28 * d2)).astype(np.float32)


# ---------- 混响 ----------

def _ir(dur, decay, predelay, seed, damp=0.35):
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.normal(0, 1, n).astype(np.float32)
    env = np.exp(-t / decay).astype(np.float32)
    ir = noise * env
    ir = lowpass(ir, 6500)
    ir = lowpass(ir, 6500)          # 两级更干净的空气吸收
    pre = np.zeros(int(predelay * SR) + 1, dtype=np.float32)
    ir = np.concatenate([pre, ir])
    ir[: len(pre)] = 0
    e = np.sqrt((ir ** 2).sum()) + 1e-9
    return (ir / e).astype(np.float32)


_IR_CACHE = {}


def reverb(x, dur=2.2, decay=0.55, predelay=0.02, seed=3, wet=0.24):
    """卷积混响：给干声一个真实空间感（很关键，程序化音色没有空间会很「贴脸」）"""
    key = (dur, decay, predelay, seed)
    if key not in _IR_CACHE:
        _IR_CACHE[key] = _ir(dur, decay, predelay, seed)
    ir = _IR_CACHE[key]
    w = fftconvolve(x, ir)[: len(x)].astype(np.float32)
    return ((1 - wet) * x + wet * w).astype(np.float32)


def stereo_widen(l, r, amount=0.35):
    """中侧处理：把中置人声让出来，同时把伴奏推向两侧"""
    mid = (l + r) * 0.5
    side = (l - r) * 0.5 * (1 + amount)
    return (mid + side).astype(np.float32), (mid - side).astype(np.float32)


# ---------- 混音 ----------

def add(buf, sig, at_smp):
    """叠加，带边界裁剪；返回叠加后的长度"""
    at = max(0, int(at_smp))
    if at >= len(buf):
        return
    n = min(len(buf) - at, len(sig))
    if n <= 0:
        return
    buf[at:at + n] += sig[:n]


def place(buf, sig, at_smp):
    """叠加并自动扩展缓冲区（用于人声这类需要完整保留的信号）"""
    at = max(0, int(at_smp))
    need = at + len(sig)
    if need > len(buf):
        buf = np.concatenate([buf, np.zeros(need - len(buf), dtype=np.float32)])
    buf[at:at + len(sig)] += sig
    return buf


def fade(x, fin=0.02, fout=0.05):
    n = len(x)
    a, b = int(fin * SR), int(fout * SR)
    if a and a * 2 < n:
        x[:a] *= np.linspace(0, 1, a, dtype=np.float32)
    if b and b * 2 < n:
        x[-b:] *= np.linspace(1, 0, b, dtype=np.float32)
    return x


def normalize(x, peak=0.92):
    m = float(np.max(np.abs(x))) or 1.0
    return (x * (peak / m)).astype(np.float32)


def dither(x, bits=16, seed=0):
    """写 16bit 前的 TPDF 抖动，避免量化噪声变成可闻的「颗粒」"""
    rng = np.random.default_rng(seed)
    lsb = 1.0 / (2 ** (bits - 1))
    n = rng.uniform(-1, 1, len(x)) - rng.uniform(-1, 1, len(x))
    return (x + n * lsb * 0.5).astype(np.float32)


def note(semi):
    """半音数 -> 频率，0 = A4 = 440Hz"""
    return 440.0 * (2 ** (semi / 12.0))
