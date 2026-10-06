# -*- coding: utf-8 -*-
"""串起「配乐 -> 旁白时间轴 -> 混音 -> 响度归一」这一步"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import timeline as TL
import mix

def main(root):
    ndir = os.path.join(root, "narration")
    scenes, total = TL.load(ndir)
    tl = TL.voice_timeline(scenes, ndir)
    raw = os.path.join(root, "audio", "mix_raw.wav")
    out = os.path.join(root, "audio", "mix.wav")
    mix.build_mix_timeline(tl, os.path.join(root, "audio", "bgm.wav"), raw, total)
    mix.loudnorm(raw, out)
    print("混音完成 ->", out)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/workspace/.video")
