# -*- coding: utf-8 -*-
"""编码与合成：帧序列 -> 带转场的视频 -> 与配音/配乐混流成片"""
import json, os, subprocess, sys, shutil

FPS = 30
XFADE = 0.6            # 转场时长
CRF = 20


def _run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def encode_scene(frames_dir, si, out_mp4, fps=FPS):
    _run(["ffmpeg", "-v", "error", "-y",
          "-framerate", str(fps),
          "-i", os.path.join(frames_dir, "s%02d" % si, "%05d.jpg"),
          "-c:v", "libopenh264", "-b:v", "6M", "-g", "60",
          "-pix_fmt", "yuv420p", out_mp4])


def concat(parts, out_mp4):
    """无缝拼接：直接 concat demuxer。

    这里故意不用 xfade —— 转场会吃掉时长，导致画面比旁白快 0.6s/幕，
    越到后面错得越多。片子本身每幕自带缓入缓出，硬切已经足够顺。
    """
    lst = out_mp4 + ".txt"
    with open(lst, "w") as f:
        for p in parts:
            f.write("file '%s'\n" % os.path.abspath(p))
    _run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0",
          "-i", lst, "-c", "copy", out_mp4])
    os.remove(lst)


def check_drift(meta, xfade=XFADE):
    """自检：拼接后的总时长必须与时间轴一致（硬切 = sum(dur)）"""
    d = [m["n"] / FPS for m in meta]
    return sum(d)


def mux(video_mp4, audio_wav, out_mp4, out_audio_aac=True):
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", video_mp4, "-i", audio_wav,
           "-map", "0:v:0", "-map", "1:a:0"]
    cmd += ["-c:v", "copy"]
    if out_audio_aac:
        cmd += ["-c:a", "aac", "-b:a", "192k", "-ar", "48000"]
    cmd += ["-shortest", "-movflags", "+faststart", out_mp4]
    _run(cmd)


def build(frames_dir, meta_path, audio_wav, workdir, out_mp4):
    os.makedirs(workdir, exist_ok=True)
    meta = json.load(open(meta_path))
    parts = []
    durs = []
    for m in meta:
        p = os.path.join(workdir, "s%02d.mp4" % m["si"])
        encode_scene(frames_dir, m["si"], p)
        parts.append(p)
        durs.append(m["n"] / FPS)
    joined = os.path.join(workdir, "video_silent.mp4")
    total = check_drift(meta)
    concat(parts, joined)
    got = float(subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", joined]).strip())
    if abs(got - total) > 0.15:
        print("警告：画面 %.2fs 与时间轴 %.2fs 偏差过大" % (got, total))
    else:
        print("画面时长 %.2fs（时间轴 %.2fs）✓" % (got, total))
    mux(joined, audio_wav, out_mp4)
    return out_mp4


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", default="/workspace/.video/frames")
    ap.add_argument("--meta", default="/workspace/.video/frames/meta.json")
    ap.add_argument("--audio", default="/workspace/.video/audio/mix.wav")
    ap.add_argument("--work", default="/tmp/enc")
    ap.add_argument("--out", default="/workspace/.video/out/safeline_intro.mp4")
    a = ap.parse_args()
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    build(a.frames, a.meta, a.audio, a.work, a.out)
    print("成片 ->", a.out)
