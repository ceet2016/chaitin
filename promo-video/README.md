# 二次元风格中文介绍视频

为「雷池 WAF 社区版 · Android 客户端」制作的 3 分 28 秒二次元风格中文介绍片。
全部画面由 Python(Pillow) 逐帧程序化绘制，配乐由 Python(numpy) 程序化合成，
无需任何外部素材或素材授权。

## 成片

- 分辨率：1920×1080 @ 30fps
- 时长：208.4 秒（3:28）
- 编码：H.264(OpenH264) + AAC 立体声
- 文件：`safeline_intro.mp4`

## 分镜（12 幕）

| # | 场景 | 时长 | 内容 |
|---|------|-----|------|
| 1 | open | 12s | 片头：盾牌徽记 + 标题 |
| 2 | hook | 14s | 悬念：服务器每晚都在被扫描 |
| 3 | intro | 15s | 角色「雷小池」登场自我介绍 |
| 4 | what | 17s | 它是什么：原生安卓管理端 |
| 5 | feat1 | 21s | 多实例 / 一键切换（附真实截图） |
| 6 | feat2 | 22s | 仪表盘 / 安全态势（附真实截图） |
| 7 | feat3 | 21s | 两种接入方式（附真实截图） |
| 8 | mods | 24s | 防护模块九宫格 |
| 9 | log | 20s | 日志中心：结构化详情 |
| 10 | safe | 18s | 安全设计：Keystore 加密 |
| 11 | dl | 15s | 下载指引 |
| 12 | end | 16s | 片尾：角色 + 仓库地址 |

## 生产流程

```bash
pip install pillow numpy
# 1. 渲染全部帧（多进程，约 5 分钟）
python render.py            # 输出到 frames/s{场景号}/{帧号}.jpg + meta.json
# 2. 合成 BGM
python bgm.py 208.4         # 输出 audio/bgm.wav
# 3. 编码各场景片段
#    ffmpeg -framerate 30 -i frames/s00/%05d.jpg -c:v libopenh264 ...
# 4. xfade 转场拼接 + 混音
#    ffmpeg ... -filter_complex "[0:v][1:v]xfade=..." out/safeline_intro.mp4
```

## 模块

| 文件 | 职责 |
|------|------|
| `theme.py` | 配色、渐变、发光、卡片、文字排版（含缓存） |
| `character.py` | Q 版角色「雷小池」矢量绘制（眨眼/口型/挥手/呆毛动画） |
| `bgfx.py` | 背景特效：渐变天空、樱花瓣粒子、光晕、太阳射线 |
| `ui.py` | 伪 UI 组件：手机壳、指标卡、徽章、标题 |
| `storyboard.py` | 12 幕分镜脚本与文案 |
| `render.py` | 逐帧渲染引擎（多进程） |
| `bgm.py` | 程序化 BGM 合成 |

## 说明

> 本构建用 ffmpeg 无 `libx264`，H.264 编码走 `libopenh264`。
> 字体使用系统内置 `WenQuanYi Zen Hei`（版权不涉第三方素材）。
> 成片为无声讲解 + 字幕/文案呈现，未使用真人配音。
