# -*- coding: utf-8 -*-
"""成片音频合成：人声 + 配乐 + 侧链闪避 + 母带

流程：
  1. 按时间轴把每条解说放到对应位置，得到一条完整旁白轨
  2. 人声链：高通去隆隆 → 宽带压缩贴耳 → 去齿音 → 3.2k 提清晰度
  3. 用旁白包络驱动配乐做 ducking（人声开口，伴奏平滑下沉 6.5dB）
  4. 混音 → 母线轻压缩 → 峰值限制 → loudnorm 统一到 -16 LUFS
"""
import numpy as np
import os, subprocess, sys, json, wave

from dsp import (SR, to_sr, place, normalize, dither, lowpass, highpass, peak_eq,
                 compress, deesser, rms_env, smooth_env, sidechain_gain)

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


def vocal_track(timeline, n_smp):
    buf = np.zeros(n_smp, dtype=np.float32)
    for path, t in timeline:
        x, srin = _read_wav_mono(path)
        x = _resample(x, srin, SR)
        place(buf, x, to_sr(t))
    return buf


def voice_chain(x):
    """人声处理链：只在人声轨上做，别去动配乐"""
    x = highpass(x, 85, 2)                     # 去隆隆
    x, _ = compress(x, thr=0.09, ratio=3.2, attack=0.006, release=0.16, makeup=1.9)
    x = deesser(x)                             # 齿音
    x = peak_eq(x, 3200, 1.1, 2.0)             # 清晰度
    x = peak_eq(x, 240, 1.2, -1.5)             # 去箱声
    x = highpass(x, 100, 1)
    return normalize(x, 0.86)


def speech_rms_from_signal(x, win=0.025, hop=0.010, sr=SR):
    e, h = rms_env(np.abs(x), win, sr, hop)
    m = float(np.percentile(e, 97)) or 1.0
    e = np.clip(e / m, 0, 1.4)
    e = np.clip((e - 0.05) / 0.5, 0, 1)
    return e, h


def duck_gain(vocal_env, hop, n_smp, depth_db=6.5, smooth_db=1.5):
    return sidechain_gain(vocal_env, hop, n_smp, depth_db=depth_db, atk=0.006, rel=0.40)


def _to_stereo(x):
    return np.stack([x, x], axis=1)


def build_mix_timeline(timeline, bgm_path, out_wav, total_sec,
                       vocal_gain=1.0, bgm_gain=1.0, duck_db=6.5):
    n_smp = to_sr(total_sec + 0.8)
    voc = vocal_track(timeline, n_smp)
    voc = voice_chain(voc)

    env, hop = speech_rms_from_signal(voc)
    gain = duck_gain(env, hop, n_smp, depth_db=duck_db)

    bg, srb = _read_wav_mono(bgm_path)
    if srb != SR:
        bg = _resample(bg, srb, SR)
    bg = np.pad(bg, (0, max(0, n_smp - len(bg))))[:n_smp]

    # 配乐让低频给人声基频；侧链闪避
    bg_mix = highpass(bg, 45, 1) * gain
    bg_mix, _ = compress(bg_mix, thr=0.30, ratio=2.0, attack=0.02, release=0.30, makeup=1.25)

    voc_st = _to_stereo(voc * vocal_gain)
    bg_st = np.stack([bg_mix, bg_mix], axis=1) * bgm_gain
    mix = voc_st + bg_st
    mix = _limit(mix, 0.965)
    write_wav_float(out_wav, mix)
    return out_wav


def _limit(x, ceiling=0.985):
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
