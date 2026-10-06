# -*- coding: utf-8 -*-
"""成片音频合成：人声 + 配乐 + 侧链闪避 + 统一响度

流程：
  1. 按时间轴把每条解说放到对应位置，得到一条完整旁白轨
  2. 用旁白的包络驱动配乐做 ducking（人声开口，伴奏自动下沉 8~10dB）
  3. 混音 -> 轻压缩 -> loudnorm 统一到 -16 LUFS（网络视频通用）
"""
import numpy as np
import os, subprocess, sys, json, wave
from dsp import SR, to_sr, place, normalize, dither, fade, lowpass

TARGET_LUFS = -16.0
TP = -1.5


def _read_wav_mono(path):
    w = wave.open(path)
    sr = w.getframerate()
    ch = w.getnchannels()
    sw = w.getsampwidth()
    raw = w.readframes(w.getnframes())
    if sw == 2:
        d = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768
    else:
        d = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648
    if ch > 1:
        d = d.reshape(-1, ch).mean(axis=1)
    return d, sr


def _resample(x, sr_in, sr_out=SR):
    if sr_in == sr_out:
        return x.astype(np.float32)
    n_out = int(len(x) * sr_out / sr_in)
    xp = np.linspace(0, len(x) - 1, n_out)
    return np.interp(xp, np.arange(len(x)), x).astype(np.float32)


def speech_rms(path, sr=SR, win=0.025, hop=0.010):
    """返回 (包络, sr)，用于驱动 ducking"""
    x, srin = _read_wav_mono(path)
    x = _resample(x, srin, sr)
    w, h = to_sr(win, sr), to_sr(hop, sr)
    n = max(1, (len(x) - w) // h)
    env = np.zeros(n, dtype=np.float32)
    for i in range(n):
        seg = x[i * h: i * h + w]
        env[i] = float(np.sqrt((seg ** 2).mean()))
    # 5 点平滑，去掉音节间的小坑
    k = np.ones(5, dtype=np.float32) / 5
    env = np.convolve(env, k, mode="same")
    # 归一化后做「软」拐点，避免一开口就全沉
    m = float(np.percentile(env, 97)) or 1.0
    env = np.clip(env / m, 0, 1.4)
    env = np.clip((env - 0.06) / 0.55, 0, 1)
    return env, h


def vocal_track(timeline, n_smp):
    """timeline: [(wav_path, start_sec)] -> 完整旁白轨"""
    buf = np.zeros(n_smp, dtype=np.float32)
    for path, t in timeline:
        x, srin = _read_wav_mono(path)
        x = _resample(x, srin, SR)
        place(buf, x, to_sr(t))
    return buf


def duck_gain(vocal_env, hop, n_smp, depth_db=9.0, smooth_db=1.5):
    """由人声包络生成配乐增益曲线（1.0 = 不动，越小越沉）"""
    depth = 10 ** (-depth_db / 20.0)
    g = 1.0 - (1.0 - depth) * vocal_env
    # 扩展回采样点
    idx = np.arange(len(g)) * hop
    gs = np.interp(np.arange(n_smp), idx, g)
    # 让增益变化慢一点，听不出「抽气」
    k = to_sr(0.28)
    kern = np.ones(k, dtype=np.float32) / k
    gs = np.convolve(gs, kern, mode="same")
    return gs.astype(np.float32)


def _to_stereo(x):
    return np.stack([x, x], axis=1)


def build_mix(narration_json, bgm_path, out_wav, total_sec,
              vocal_gain=1.0, bgm_gain=1.0, duck_db=9.0):
    n_smp = to_sr(total_sec + 0.5)

    # --- 旁白时间轴 ---
    # narration.json: {key: {wav, dur, gap, at}}；at 是相对本场景起点的偏移
    # 场景起点由 durations/scene 时间轴给出；这里由调用方传入已算好的绝对时间
    raise SystemExit("use build_mix_timeline instead")


def build_mix_timeline(timeline, bgm_path, out_wav, total_sec,
                       vocal_gain=1.0, bgm_gain=1.0, duck_db=9.0):
    """timeline: [(wav_path, start_sec), ...]"""
    n_smp = to_sr(total_sec + 0.6)
    voc = vocal_track(timeline, n_smp)

    # 旁白：高通 + 轻微压缩，贴住「近讲」感
    voc = _deess(voc)

    env, hop = speech_rms_from_signal(voc)
    gain = duck_gain(env, hop, n_smp, depth_db=duck_db)

    bg, srb = _read_wav_mono(bgm_path)
    if srb != SR:
        bg = _resample(bg, srb, SR)
    bg = np.pad(bg, (0, max(0, n_smp - len(bg))))[:n_smp]

    # 配乐整体再切掉 200Hz 以下一点点，把低频让给贝斯与人声基频
    bg_mix = bg * gain

    voc_st = _to_stereo(voc * vocal_gain)
    bg_st = np.stack([bg_mix, bg_mix], axis=1) * bgm_gain

    mix = voc_st + bg_st
    mix = _limit(mix, 0.985)
    write_wav_float(out_wav, mix)
    return out_wav


def _deess(x):
    """极简去齿音：只在 6~10k 做动态衰减"""
    from scipy.signal import lfilter, butter
    b, a = butter(2, [6000 / (SR / 2), 10000 / (SR / 2)], btype="band")
    hi = lfilter(b, a, x).astype(np.float32)
    # 高频包络
    n = len(hi)
    env = np.abs(hi)
    k = to_sr(0.004)
    kern = np.ones(k, dtype=np.float32) / k
    e = np.convolve(env, kern, mode="same")
    thr = np.percentile(e, 96) * 0.85 + 1e-6
    red = np.clip(thr / (e + 1e-6), 0.35, 1.0).astype(np.float32)
    return (x - hi + hi * red).astype(np.float32)


def speech_rms_from_signal(x, sr=SR, win=0.025, hop=0.010):
    w, h = to_sr(win, sr), to_sr(hop, sr)
    n = max(1, (len(x) - w) // h)
    env = np.zeros(n, dtype=np.float32)
    for i in range(n):
        seg = x[i * h: i * h + w]
        env[i] = float(np.sqrt((seg ** 2).mean()))
    k = np.ones(5, dtype=np.float32) / 5
    env = np.convolve(env, k, mode="same")
    m = float(np.percentile(env, 97)) or 1.0
    env = np.clip(env / m, 0, 1.4)
    env = np.clip((env - 0.05) / 0.5, 0, 1)
    return env, h


def _limit(x, ceiling=0.985):
    """软限幅：超过 ceiling 的部分用 tanh 折回来，比硬切干净"""
    m = np.max(np.abs(x)) or 1.0
    if m <= ceiling:
        return x.astype(np.float32)
    return (ceiling * np.tanh(x / (ceiling * 1.02))).astype(np.float32)


def write_wav_float(path, stereo):
    inter = np.empty(len(stereo) * 2, dtype=np.int16)
    inter[0::2] = np.clip(dither(stereo[:, 0], 16, 7), -1, 1) * 32767
    inter[1::2] = np.clip(dither(stereo[:, 1], 16, 8), -1, 1) * 32767
    w = wave.open(path, "w")
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(inter.tobytes())
    w.close()


def loudnorm(in_wav, out_wav, i=TARGET_LUFS, tp=TP, lra=11.0):
    """两遍 loudnorm，保证响度统一且不过冲"""
    p1 = subprocess.run(
        ["ffmpeg", "-hide_banner", "-v", "error", "-i", in_wav,
         "-af", "loudnorm=I=%s:TP=%s:LRA=%s:print_format=json" % (i, tp, lra),
         "-f", "null", "-"],
        capture_output=True, text=True)
    info = {}
    txt = p1.stderr
    if "{" in txt:
        info = json.loads(txt[txt.rindex("{"):txt.rindex("}") + 1])
    if info.get("input_i"):
        af = ("loudnorm=I=%s:TP=%s:LRA=%s:"
              "measured_I=%s:measured_TP=%s:measured_LRA=%s:measured_thresh=%s:"
              "offset=%s:linear=true" % (
                  i, tp, lra, info["input_i"], info["input_tp"],
                  info["input_lra"], info["input_thresh"],
                  info.get("target_offset", "0")))
    else:
        af = "loudnorm=I=%s:TP=%s:LRA=%s" % (i, tp, lra)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", in_wav,
                    "-af", af, "-ar", str(SR), "-ac", "2", out_wav], check=True)
    return out_wav
