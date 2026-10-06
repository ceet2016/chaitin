# -*- coding: utf-8 -*-
"""基础 DSP 积木：滤波、混响、饱和、动态处理、母带

纯 numpy/scipy 实现，不依赖任何外部素材，保证 CI 可复现。
"""
import numpy as np
from scipy.signal import lfilter, butter, fftconvolve, sosfilt, sosfilt_zi

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


def peak_eq(x, freq, q, gain_db, sr=SR):
    """参数式峰值均衡（RBJ cookbook），用于扫掉混叠杂音 / 提亮"""
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * np.pi * freq / sr
    alpha = np.sin(w0) / (2 * q)
    b = np.array([1 + alpha * A, -2 * np.cos(w0), 1 - alpha * A])
    a = np.array([1 + alpha / A, -2 * np.cos(w0), 1 - alpha / A])
    return lfilter(b / a[0], a / a[0], x).astype(np.float32)


def high_shelf(x, freq, gain_db, sr=SR):
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * np.pi * freq / sr
    alpha = np.sin(w0) / 2 * np.sqrt(2)
    c = 2 * np.sqrt(A) * alpha
    b = np.array([A * ((A + 1) + (A - 1) * np.cos(w0) + c),
                  -2 * A * ((A - 1) + (A + 1) * np.cos(w0)),
                  A * ((A + 1) + (A - 1) * np.cos(w0) - c)])
    a = np.array([(A + 1) - (A - 1) * np.cos(w0) + c,
                  -2 * ((A - 1) - (A + 1) * np.cos(w0)),
                  (A + 1) - (A - 1) * np.cos(w0) - c])
    return lfilter(b / a[0], a / a[0], x).astype(np.float32)


# ---------- 非线性 ----------

def soft_sat(x, drive=1.2):
    """tanh 软饱和：加偶次谐波，听感更「实」"""
    return np.tanh(x * drive).astype(np.float32)


def tape_sat(x, drive=1.6, mix=0.45):
    """带二次谐波的磁带饱和，让单音不再像蜂鸣器"""
    d = np.tanh(x * drive).astype(np.float32)
    d2 = np.tanh(x * drive * 0.55).astype(np.float32)
    return ((1 - mix) * x + mix * (0.72 * d + 0.28 * d2)).astype(np.float32)


# ---------- 混响 ----------

def _ir(dur, decay, predelay, seed, damp=6500):
    """指数衰减噪声脉冲响应；damp 越低越「贴身」"""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.exp(-t / decay).astype(np.float32)
    ir = (rng.normal(0, 1, n).astype(np.float32)) * env
    ir = lowpass(ir, damp)
    # early reflection：两三个早期抽头，空间更「有形」
    for dt, g in ((0.011, 0.5), (0.019, 0.35), (0.031, 0.22)):
        i = int(dt * SR)
        if i < n:
            ir[i] += g
    pre = np.zeros(int(predelay * SR) + 1, dtype=np.float32)
    ir = np.concatenate([pre, ir])
    e = np.sqrt((ir ** 2).sum()) + 1e-9
    return (ir / e).astype(np.float32)


_IR_CACHE = {}


def reverb(x, dur=2.2, decay=0.55, predelay=0.02, seed=3, wet=0.24, damp=6500):
    """卷积混响：给干声一个真实空间感"""
    key = (dur, decay, predelay, seed, damp)
    if key not in _IR_CACHE:
        _IR_CACHE[key] = _ir(dur, decay, predelay, seed, damp)
    ir = _IR_CACHE[key]
    w = fftconvolve(x, ir)[: len(x)].astype(np.float32)
    return ((1 - wet) * x + wet * w).astype(np.float32)


def stereo_widen(l, r, amount=0.35):
    """中侧处理：把中置人声让出来，同时把伴奏推向两侧"""
    mid = (l + r) * 0.5
    side = (l - r) * 0.5 * (1 + amount)
    return (mid + side).astype(np.float32), (mid - side).astype(np.float32)


# ---------- 动态 ----------

def rms_env(x, win=0.05, sr=SR, hop=0.01):
    """返回 (包络, hop 样点数)，包络已做峰值化处理便于当控制信号"""
    w, h = to_sr(win, sr), to_sr(hop, sr)
    n = max(1, (len(x) - w) // h)
    e = np.zeros(n, dtype=np.float32)
    for i in range(n):
        seg = x[i * h: i * h + w]
        e[i] = float(np.sqrt((seg ** 2).mean()))
    return e, h


def smooth_env(e, hop, n_smp, win_sec=0.06, sr=SR):
    """把低速率包络平滑并上采样回样点级"""
    k = max(1, to_sr(win_sec, sr) // max(1, hop))
    kern = np.ones(k, dtype=np.float32) / k
    e = np.convolve(e, kern, mode="same")
    return np.interp(np.arange(n_smp), np.arange(len(e)) * hop, e).astype(np.float32)


def compress(x, thr=0.3, ratio=3.0, attack=0.008, release=0.18, makeup=1.0, sr=SR):
    """前馈软拐点压缩器：人声贴面、伴奏不炸"""
    env, hop = rms_env(x, 0.02, sr, 0.005)
    e = smooth_env(env, hop, len(x), 0.03, sr)
    knee = 0.5
    g = np.ones_like(e)
    over = e - thr
    soft = np.where(over > -knee, np.maximum(0.0, over + knee) ** 2 / (2 * knee * ratio), 0.0)
    g = 1.0 / (1.0 + np.maximum(0.0, over) * (ratio - 1) / np.maximum(e, 1e-6) * 0.0 + 0.0)
    # 上面那行只为保持可读性，真正的增益曲线用下面统一实现
    gr = np.zeros_like(e)
    idx = over > 0
    gr[idx] = (over[idx] * (1 - 1 / ratio)) / np.maximum(e[idx], 1e-6)
    g = 1.0 - gr
    a = np.exp(-1.0 / (attack * sr / max(1, hop)))
    r = np.exp(-1.0 / (release * sr / max(1, hop)))
    sm = np.empty_like(g)
    cur = 1.0
    for i, target in enumerate(g):
        cur = cur + (target - cur) * (a if target < cur else r)
        sm[i] = cur
    gs = np.interp(np.arange(len(x)), np.arange(len(sm)) * hop, sm).astype(np.float32)
    return (x * gs * makeup).astype(np.float32), gs


def deesser(x, lo=5800, hi=9500, thr_pct=96, max_red_db=7.0, sr=SR):
    """齿音：只在 5.8~9.5k 做动态衰减，保留人声空气感"""
    b, a = butter(2, [lo / (sr / 2), hi / (sr / 2)], btype="band")
    hi_band = lfilter(b, a, x).astype(np.float32)
    env, hop = rms_env(np.abs(hi_band), 0.006, sr, 0.002)
    e = smooth_env(env, hop, len(x), 0.01, sr)
    thr = float(np.percentile(e, thr_pct)) + 1e-9
    red = np.clip(thr / (e + 1e-9), 10 ** (-max_red_db / 20.0), 1.0).astype(np.float32)
    return (x - hi_band + hi_band * red).astype(np.float32)


def gate_below(x, cutoff=0.0, sr=SR):
    """把低于 cutoff 的极低频做 12dB/oct 高通（人声轨去隆隆声）"""
    return highpass(x, cutoff, 2, sr) if cutoff > 0 else x


def sidechain_gain(vocal_env, hop, n_smp, depth_db=6.5, atk=0.004, rel=0.35, sr=SR):
    """人声驱动配乐闪避：开口即下沉，收句平滑回位"""
    depth = 10 ** (-depth_db / 20.0)
    target = 1.0 - (1.0 - depth) * np.clip(vocal_env, 0, 1)
    a = np.exp(-1.0 / max(1e-6, atk * sr / hop))
    r = np.exp(-1.0 / max(1e-6, rel * sr / hop))
    sm = np.empty_like(target)
    cur = 1.0
    for i, t in enumerate(target):
        cur = cur + (t - cur) * (a if t < cur else r)
        sm[i] = cur
    return np.interp(np.arange(n_smp), np.arange(len(sm)) * hop, sm).astype(np.float32)


# ---------- 混音 ----------

def add(buf, sig, at_smp):
    at = max(0, int(at_smp))
    if at >= len(buf):
        return
    n = min(len(buf) - at, len(sig))
    if n > 0:
        buf[at:at + n] += sig[:n]


def place(buf, sig, at_smp):
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
    rng = np.random.default_rng(seed)
    lsb = 1.0 / (2 ** (bits - 1))
    n = rng.uniform(-1, 1, len(x)) - rng.uniform(-1, 1, len(x))
    return (x + n * lsb * 0.5).astype(np.float32)


def note(semi):
    """半音数 -> 频率，0 = A4 = 440Hz"""
    return 440.0 * (2 ** (semi / 12.0))
