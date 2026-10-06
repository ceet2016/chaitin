# -*- coding: utf-8 -*-
"""时间轴：场景顺序 / 时长 / 每句解说的绝对时间

成片时长与配乐长度都从这里来，保证画面、旁白、配乐三者对齐。
"""
import json, os, sys

# 场景顺序（与 storyboard.py 的 SCENES 一致）
SCENE_ORDER = ["open", "hook", "intro", "what", "feat1", "feat2", "feat3",
               "mods", "log", "safe", "dl", "end"]

# 每个场景绑定哪句解说；None 表示本场景无旁白
SCENE_VOICE = {
    "open":  None,
    "hook":  "hook",
    "intro": "intro",
    "what":  "what",
    "feat1": "feat1",
    "feat2": "feat2",
    "feat3": "feat3",
    "mods":  "mods",
    "log":   "log",
    "safe":  "safe",
    "dl":    "dl",
    "end":   "end",
}

TAIL = 1.0          # 每句结束后的画面留白


def load(narration_dir):
    nj = os.path.join(narration_dir, "narration.json")
    with open(nj) as f:
        N = json.load(f)
    scenes = {}
    t = 0.0
    for sid in SCENE_ORDER:
        key = SCENE_VOICE[sid]
        if key is None:
            dur = 9.2
            vo_at = None
        else:
            v = N[key]
            vo_at = t + v["at"]
            dur = v["at"] + v["dur"] + v["gap"] + TAIL
        scenes[sid] = dict(start=round(t, 3), dur=round(dur, 3),
                           voice=key, voice_at=round(vo_at, 3) if vo_at else None)
        t += dur
    return scenes, round(t, 3)


def voice_timeline(scenes, narration_dir):
    """[(wav, abs_start)]，直接喂给 mix.py"""
    out = []
    for sid in SCENE_ORDER:
        s = scenes[sid]
        if not s["voice"]:
            continue
        wav = os.path.join(narration_dir, "%s.wav" % s["voice"])
        out.append((wav, s["voice_at"]))
    return out


if __name__ == "__main__":
    d = sys.argv[1] if len(sys.argv) > 1 else "/tmp/work/.video/narration"
    scenes, total = load(d)
    for k in SCENE_ORDER:
        s = scenes[k]
        print("%-6s start=%7.2f dur=%6.2f voice=%-5s at=%s" % (
            k, s["start"], s["dur"], s["voice"],
            "%.2f" % s["voice_at"] if s["voice_at"] else "-"))
    print("---\n总时长 %.2fs = %d:%02d" % (total, total // 60, total % 60))
    json.dump(dict(scenes=scenes, total=total),
              open("/tmp/work/.video/timeline.json", "w"), ensure_ascii=False, indent=1)
