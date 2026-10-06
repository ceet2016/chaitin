# -*- coding: utf-8 -*-
"""中文配音脚本：解说文案 + Edge-TTS 合成 + 句式校正（去机械感）

本模块把「谁在第几秒说什么」集中定义，成片与字幕共用同一份数据，
避免旁白和字幕对不上的问题。
"""
import asyncio, os, subprocess, json, shutil

VOICE = "zh-CN-XiaoxiaoNeural"  # 温柔女声；v4 换掉 Xiaoyi（实测 6kHz 以上能量 27%，沙沙地刺耳）
RATE  = "-4%"                     # 放慢一点，娓娓道来；v3 的 +8% 又急又尖
PITCH = "-2Hz"                    # 不再抬调；v3 的 +25Hz 把共振峰整体上移，是「刺」的来源之一

# ---------- 解说词 ----------
# id: 句子标识；text: 文案；gap: 句后停顿(秒)
NARRATION = {
    "hook": dict(text="每天晚上，你的服务器都在被扫描。", gap=0.4, at=0.8),
    "intro": dict(
        text="我叫雷小池，是你的安全小助手。今天要介绍的，是雷池 WAF 社区版的原生安卓客户端。",
        gap=0.4, at=0.6),
    "what": dict(
        text="它是什么？简单说，就是把雷池的控制台装进手机。"
             "用 Kotlin 和 Jetpack Compose 写的原生应用，"
             "支持安卓八点零以上。不用守在电脑前，掏出手机就能查看安全态势。",
        gap=0.45, at=0.7),
    "feat1": dict(
        text="先说多实例。多台 WAF，一个应用全部管完。"
             "顶部面板随时切换、新增、编辑、删除。"
             "密码、API Token、动态验证密钥，"
             "全部由安卓 Keystore 以 AES-256-GCM 加密落盘，不存明文。"
             "账号库还排除了云端备份，换手机也不会泄露。",
        gap=0.4, at=0.7),
    "feat2": dict(
        text="再说仪表盘。进入页面自动刷新，二十秒轮询一次。"
             "实时访问趋势、实时拦截趋势、实时 QPS，三张图滚动更新。"
             "防护拦截、防护资源、防护能力，三组指标卡都可以点击跳转。"
             "下方是最新安全事件流，攻击动态实时滚动，一眼看清当前态势。",
        gap=0.4, at=0.7),
    "feat3": dict(
        text="接入方式有两种。"
             "会话模式复刻控制台真实登录链路，用户名、密码、两步验证，"
             "功能最完整；保存了动态验证密钥之后，"
             "应用会自动生成六位动态码，不用再翻验证器。"
             "API Token 模式走官方稳定接口，自动化场景首选。",
        gap=0.4, at=0.7),
    "mods": dict(
        text="防护能力这块，常用的模块一个不少。"
             "多账号切换、仪表盘、防护站点、IP 组、黑白名单、"
             "自定义规则、频率限制、日志中心、证书管理。"
             "从站点配置到日志排查，手机上都能完成。",
        gap=0.45, at=0.6),
    "log": dict(
        text="日志中心是我最喜欢的地方。"
             "攻击发生了，细节在哪？点开攻击事件，会二次请求完整详情，"
             "按概要、攻击特征、请求内容、其他字段分组展示。"
             "原始 JSON 可以展开，也可以一键复制。"
             "攻击事件、频率控制、人机验证、身份认证、访问日志，一共五类。"
             "排查取证，都方便。",
        gap=0.4, at=0.7),
    "safe": dict(
        text="安全，是它的底色。"
             "凭据用 Keystore 的 AES-256-GCM 加密存储；"
             "账号库排除出云端备份，杜绝泄露；"
             "登录令牌只留在内存里，进程结束立刻失效。",
        gap=0.45, at=0.6),
    "dl": dict(
        text="下载就能用。每次推送自动构建，发布最新的安装包。"
             "地址在屏幕上，扫码或者点开仓库都能下载。",
        gap=0.4, at=0.8),
    "end": dict(
        text="雷池 WAF 客户端。为你的防线，站好每一班岗。",
        gap=0.6, at=1.2),
}


def _ffprobe_dur(path):
    out = subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", path])
    return float(out.strip())


async def _synth_one(key, item, outdir):
    import edge_tts
    mp3 = os.path.join(outdir, f"{key}.mp3")
    if not os.path.exists(mp3):
        c = edge_tts.Communicate(item["text"], VOICE, rate=RATE, pitch=PITCH)
        await c.save(mp3)
    wav = os.path.join(outdir, f"{key}.wav")
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", mp3,
         "-af", "highpass=f=95,afftdn=nf=-28,equalizer=f=2800:t=q:w=1.2:g=-2,equalizer=f=7000:t=h:w=2000:g=-3,deesser,acompressor=threshold=-16dB:ratio=2.4:attack=8:release=180,alimiter=limit=0.95",
         "-ar", "48000", "-ac", "1", wav], check=True)
    return key, wav, _ffprobe_dur(wav)


def synth_all(outdir):
    """合成全部解说句，返回 {key: {wav, dur, gap, at}}"""
    os.makedirs(outdir, exist_ok=True)
    async def _run():
        return await asyncio.gather(
            *[_synth_one(k, v, outdir) for k, v in NARRATION.items()])
    results = asyncio.run(_run())
    out = {}
    for key, wav, dur in results:
        out[key] = dict(wav=wav, dur=dur,
                        gap=NARRATION[key]["gap"], at=NARRATION[key]["at"])
    return out


if __name__ == "__main__":
    import sys
    d = sys.argv[1] if len(sys.argv) > 1 else "/workspace/.video/narration"
    res = synth_all(d)
    total = sum(v["dur"] + v["gap"] for v in res.values())
    for k, v in res.items():
        print("%-6s %6.2fs  %s" % (k, v["dur"], NARRATION[k]["text"][:22]))
    print("---")
    print("旁白净时长 %.1fs（%d:%02d）" % (total, total // 60, total % 60))
    json.dump(res, open(os.path.join(d, "narration.json"), "w"),
              ensure_ascii=False, indent=1)
