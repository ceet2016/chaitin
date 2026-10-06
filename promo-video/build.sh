#!/usr/bin/env bash
# 一键重建介绍视频：配音 -> 配乐 -> 画面 -> 混流
# 依赖：python3, pillow, numpy, scipy, edge-tts, ffmpeg
set -euo pipefail
cd "$(dirname "$0")"
ROOT="${VIDEO_ROOT:-/workspace/.video}"
mkdir -p "$ROOT"/{narration,audio,frames,out}

echo "[1/5] 合成中文配音 ..."
python3 voice.py "$ROOT/narration"

echo "[2/5] 计算时间轴与字幕 ..."
python3 timeline.py "$ROOT/narration"
python3 subtitles.py "$ROOT/narration"

echo "[3/5] 合成配乐 ..."
TOTAL=$(python3 -c "import json;print(json.load(open('$ROOT/timeline.json'))['total'])")
python3 bgm.py "$TOTAL" "$ROOT/audio/bgm.wav"

echo "[4/5] 渲染画面（多进程）..."
VIDEO_FRAMES="$ROOT/frames" VIDEO_SHOTS="${VIDEO_SHOTS:-/workspace/docs}" python3 render.py

echo "[5/5] 混音并合成成片 ..."
python3 mixrun.py "$ROOT"
python3 encode.py --frames "$ROOT/frames" --meta "$ROOT/frames/meta.json" \
    --audio "$ROOT/audio/mix.wav" --work /tmp/enc --out "$ROOT/out/safeline_intro.mp4"

echo "完成 -> $ROOT/out/safeline_intro.mp4"
