# -*- coding: utf-8 -*-
import json, os, subprocess, re

FF = r".\tmp_ffmpeg\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v9.0.2.exe"
OUT = "demo/out"
plan = json.load(open("demo/clips/plan.json", encoding="utf-8"))
XF = 0.25

# 与 demo_build.py 中一致的素材表（源内时间）
ASSET = {
 "talk_main":  ("talk.mp4", 2.99, 7.02, "ask"),
 "talk_mid":   ("talk.mp4", 3.50, 6.60, "ask"),
 "talk_short": ("talk.mp4", 3.50, 5.50, "ask"),
 "talk_emph":  ("talk.mp4", 5.50, 6.60, "ask-emphasis"),
 "talk_wrap":  ("talk.mp4", 5.50, 7.02, "ask-short"),
 "talk_end":   ("talk.mp4", 4.00, 7.02, "wrap"),
 "talk_settle":("talk.mp4", 8.94, 10.00, "hold-gaze"),
 "listen_a":   ("idle.mp4", 3.30, 5.00, "listen"),
 "listen_b":   ("idle.mp4", 8.40, 9.60, "listen"),
 "nod":        ("idle.mp4", 4.60, 6.60, "nod"),
}

rows, acc = [], 0.0
for i, p in enumerate(plan):
    st = 0.0 if i == 0 else acc + XF
    src = ASSET.get(p["key"], ("?", 0, 0, "?"))
    rows.append({
        "id": p["id"], "state": p["kind"], "asset": p["key"],
        "src_file": src[0], "src_in": src[1], "src_out": src[2], "role": src[3],
        "timeline_start": round(st, 3), "duration": p["dur"],
        "tts": p["tts"], "caption": p["caption"],
    })
    acc += p["dur"] - XF

total = acc + XF
meta = {
    "project": "Nova AI 模拟面试 · 数字人观感验证片",
    "source_material": {
        "talk.mp4": {"resolution": "960x960", "fps": 24.06, "duration": 10.017,
                     "codec": "h264 High yuv420p", "audio": "none"},
        "idle.mp4": {"resolution": "960x960", "fps": 24.06, "duration": 10.017,
                     "codec": "h264 High yuv420p", "audio": "none"},
    },
    "output": {"file": "nova_interview_demo_1080p.mp4", "resolution": "1920x1080",
               "fps": 25, "duration": round(total, 3), "audio": "AAC 48k stereo (edge-tts 合成)"},
    "render_rules": {
        "avatar_crop": "crop=660:840:300:20（避开源内左上黑边蒙版与右下平台水印）",
        "crossfade": 0.25,
        "talk_fit": "按 TTS 时长适配：≤1.22 倍变速优先，超长才循环，偏长则截尾",
    },
    "shots": rows,
}
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "segments.json"), "w", encoding="utf-8") as fh:
    json.dump(meta, fh, ensure_ascii=False, indent=2)

r = subprocess.run([FF, "-hide_banner", "-i", os.path.join(OUT, "nova_interview_demo_1080p.mp4")],
                   capture_output=True, text=True, encoding="utf-8", errors="replace")
print("\n".join(l for l in r.stderr.splitlines() if "Duration" in l or "Stream" in l))
print("shots:", len(rows), "total:", round(total, 3))
