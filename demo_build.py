# -*- coding: utf-8 -*-
"""Nova AI 模拟面试 · 观感验证片构建。不改动原始素材，只在 demo/ 下产出。"""
import subprocess, re, os, json, sys, shutil

FF = r".\tmp_ffmpeg\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v9.0.2.exe"
ROOT = os.path.abspath(".")
SRC = {"talk": "demo/src/talk.mp4", "idle": "demo/src/idle.mp4"}
CLIPS = "demo/clips"
TTS = "demo/tts"
OUT = "demo/out"
FPS = 25
XF = 0.25
FONT_BD = "C\\:/Windows/Fonts/msyhbd.ttc"
FONT = "C\\:/Windows/Fonts/msyh.ttc"

def run(args, fail=True):
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if fail and r.returncode != 0:
        print("FAIL:", " ".join(args)[:300]); print(r.stderr[-2000:]); sys.exit(1)
    return r

def dur(path):
    r = subprocess.run([FF, "-hide_banner", "-i", path], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    m = re.search(r"Duration: (\d+):(\d+):(\d+\.\d+)", r.stderr)
    if not m: return 0.0
    h, mi, s = m.groups()
    return int(h)*3600 + int(mi)*60 + float(s)

LINES = [
    ("l1", "你好，我是今天的面试官。"),
    ("l2", "先聊聊你最近的项目，你负责哪一部分？"),
    ("l3", "当时为什么选这个方案？"),
    ("l4", "再具体一点。"),
    ("l5", "好，先到这里，下一轮再深入。"),
]
RATES = {"l1": "+6%", "l2": "+8%", "l3": "+6%", "l4": "+2%", "l5": "+6%"}

def stage_tts():
    sys.path.insert(0, r'.\tmp_pydeps')
    import asyncio, edge_tts
    async def gen():
        for name, text in LINES:
            await edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural", rate=RATES[name]).save(
                os.path.join(TTS, name + ".mp3"))
    asyncio.run(gen())
    for name, _ in LINES:
        src = os.path.join(TTS, name + ".mp3")
        dst = os.path.join(TTS, name + ".wav")
        run([FF, "-hide_banner", "-loglevel", "error", "-i", src,
             "-af", "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,"
                    "areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,areverse",
             "-ar", "48000", "-ac", "2", "-y", dst])
    return {n: dur(os.path.join(TTS, n + ".wav")) for n, _ in LINES}

CROP = "crop=660:840:300:20"
NAMES = {
 "talk_main":  ("talk", 2.99, 7.02),
 "talk_mid":   ("talk", 3.50, 6.60),
 "talk_short": ("talk", 3.50, 5.50),
 "talk_emph":  ("talk", 5.50, 6.60),
 "talk_wrap":  ("talk", 5.50, 7.02),
 "talk_end":   ("talk", 4.00, 7.02),
 "talk_settle":("talk", 8.94, 10.00),
 "listen_a":   ("idle", 3.30, 5.00),
 "listen_b":   ("idle", 8.40, 9.60),
 "nod":        ("idle", 4.60, 6.60),
}
SEG = {}

def stage_segments():
    for k, (src, i, o) in NAMES.items():
        p = os.path.join(CLIPS, k + ".mp4")
        run([FF, "-hide_banner", "-loglevel", "error", "-ss", str(i), "-to", str(o),
             "-i", SRC[src], "-vf", f"{CROP},fps={FPS},setpts=PTS-STARTPTS",
             "-an", "-c:v", "libx264", "-crf", "16", "-pix_fmt", "yuv420p", "-y", p])
        SEG[k] = {"path": p, "dur": dur(p)}
    return SEG

def shot_from_segment(key, target, dst):
    """把单个片段适配到目标时长：轻微变速优先，短了才循环，长了就截尾。"""
    native = SEG[key]["dur"]
    r = target / native
    if r <= 1.22:
        run([FF, "-hide_banner", "-loglevel", "error", "-i", SEG[key]["path"],
             "-vf", f"setpts=PTS*{r:.4f},fps={FPS},trim=duration={target:.3f},setpts=PTS-STARTPTS",
             "-an", "-c:v", "libx264", "-crf", "16", "-pix_fmt", "yuv420p", "-y", dst])
    else:
        lst = os.path.join(CLIPS, "_l.txt")
        reps = int(target / native) + 1
        with open(lst, "w", encoding="utf-8") as fh:
            for _ in range(reps):
                fh.write("file '%s'\n" % os.path.abspath(SEG[key]["path"]).replace("\\", "/"))
        run([FF, "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst,
             "-t", f"{target:.3f}", "-r", str(FPS), "-an", "-c:v", "libx264", "-crf", "16",
             "-pix_fmt", "yuv420p", "-y", dst])
    return dur(dst)

def hold_from_seq(keys, target, dst):
    lst = os.path.join(CLIPS, "_h.txt")
    files, total, i = [], 0.0, 0
    while total < target - 1e-3:
        k = keys[i % len(keys)]
        files.append(k); total += SEG[k]["dur"]; i += 1
    with open(lst, "w", encoding="utf-8") as fh:
        for k in files:
            fh.write("file '%s'\n" % os.path.abspath(SEG[k]["path"]).replace("\\", "/"))
    run([FF, "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst,
         "-t", f"{target:.3f}", "-r", str(FPS), "-an", "-c:v", "libx264", "-crf", "16",
         "-pix_fmt", "yuv420p", "-y", dst])
    return dur(dst)

def stage_shots(tts):
    plan = []
    def talk(sid, key, tts_key, caption):
        target = tts[tts_key] + 0.20
        dst = os.path.join(CLIPS, sid + ".mp4")
        plan.append({"id": sid, "kind": "talk", "key": key, "tts": tts_key,
                     "caption": caption, "dur": round(shot_from_segment(key, target, dst), 3)})
    def hold(sid, keys, seconds, caption):
        dst = os.path.join(CLIPS, sid + ".mp4")
        plan.append({"id": sid, "kind": "hold", "key": keys[0], "tts": None,
                     "caption": caption, "dur": round(hold_from_seq(keys, seconds, dst), 3)})

    talk("s01", "talk_short", "l1", "你好，我是今天的面试官。")
    hold("s02", ["listen_a", "listen_b"], 3.8, "候选人回答中…")
    talk("s03", "talk_mid", "l2", "先聊聊你最近的项目，你负责哪一部分？")
    hold("s04", ["nod"], 2.0, "点头 · 继续倾听")
    talk("s05", "talk_wrap", "l3", "当时为什么选这个方案？")
    hold("s06", ["listen_b", "listen_a"], 4.4, "候选人回答中…")
    talk("s07", "talk_emph", "l4", "再具体一点。")
    hold("s08", ["listen_a", "listen_b"], 3.2, "候选人回答中…")
    talk("s09", "talk_end", "l5", "好，先到这里，下一轮再深入。")
    hold("s10", ["talk_settle"], 1.6, "保持注视")
    hold("s11", ["listen_b", "listen_a"], 3.6, "等待下一题")
    with open(os.path.join(CLIPS, "plan.json"), "w", encoding="utf-8") as fh:
        json.dump(plan, fh, ensure_ascii=False, indent=1)
    return plan

def stage_concat(plan):
    n = len(plan)
    inputs, pre = [], []
    for s in plan:
        inputs += ["-i", os.path.join(CLIPS, s["id"] + ".mp4")]
    # xfade chain
    parts, prev = [], None
    offset = 0.0
    starts = [0.0]
    for i, s in enumerate(plan):
        if i == 0:
            prev = "[0:v]"
            continue
    filt = []
    cur = "[0:v]"
    for i in range(1, n):
        offset = sum(p["dur"] for p in plan[:i]) - i * XF
        starts.append(offset + XF)
        lab = f"[x{i}]"
        filt.append(f"{cur}[{i}:v]xfade=transition=fade:duration={XF}:offset={offset:.3f}{lab}")
        cur = lab
    fc = ";".join(filt)
    outv = os.path.join(CLIPS, "avatar_seq.mp4")
    run([FF, "-hide_banner", "-loglevel", "error", *inputs, "-filter_complex", fc,
         "-map", cur, "-r", str(FPS), "-an", "-c:v", "libx264", "-crf", "16",
         "-pix_fmt", "yuv420p", "-y", outv])
    return outv, starts

def stage_audio(plan, starts, tts, total):
    ins, filt, mix = [], [], []
    idx = 0
    for s, st in zip(plan, starts):
        if s["tts"]:
            ins += ["-i", os.path.join(TTS, s["tts"] + ".wav")]
            ms = int((st + 0.05) * 1000)
            filt.append(f"[{idx}:a]adelay={ms}|{ms},volume=1.0[a{idx}]")
            mix.append(f"[a{idx}]")
            idx += 1
    fc = ";".join(filt) + f";anullsrc=r=48000:cl=stereo,atrim=0:{total:.3f}[bed];" + \
         "".join(mix) + f"[bed]amix=inputs={idx+1}:normalize=0[mixed];" + \
         f"[mixed]atrim=0:{total:.3f},alimiter=limit=0.95,volume=1.6[aout]"
    outa = os.path.join(CLIPS, "bed.wav")
    run([FF, "-hide_banner", "-loglevel", "error", *ins, "-filter_complex", fc,
         "-map", "[aout]", "-ar", "48000", "-ac", "2", "-y", outa])
    return outa

def wrap_cn(text, width=13):
    out, line = [], ""
    for ch in text:
        if ch == "\n":
            out.append(line); line = ""; continue
        line += ch
        if len(line) >= 9 and ch in "，。？、！；":
            out.append(line); line = ""
    if line: out.append(line)
    return "\n".join(out)

def stage_ui(plan, starts, avatar, audio, total):
    W, H = 1920, 1080
    AW, AH = 760, 840          # 源裁切 795x880 -> 去掉自带水印区
    AX, AY = 1080, 112
    caps = []
    for i, (s, st) in enumerate(zip(plan, starts)):
        path = os.path.join(CLIPS, f"cap_{s['id']}.txt")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(wrap_cn(s["caption"]))
        caps.append((path, st, st + s["dur"] - 0.1, s["kind"]))
    f = []
    f.append(f"color=c=0x0D1014:s={W}x{H}:d={total:.3f}:r={FPS}[bg]")
    f.append(f"[0:v]scale={AW}:{AH},pad={AW+8}:{AH+8}:4:4:color=0x1E2632[av]")
    f.append(f"[bg][av]overlay={AX-4}:{AY-4}[base]")
    f.append("[base]drawbox=x=100:y=96:w=860:h=2:color=0x2A3442:t=fill[g1]")
    # header
    f.append(f"[g1]drawtext=fontfile='{FONT_BD}':text='Nova AI':fontsize=46:fontcolor=0xE8EEF7:x=100:y=120[g2]")
    f.append(f"[g2]drawtext=fontfile='{FONT}':text='模拟面试 · AI 面试官':fontsize=28:fontcolor=0x7F8B9E:x=345:y=140[g3]")
    f.append(f"[g3]drawtext=fontfile='{FONT}':text='实时语音对话 / 数字人形象 / 追问评分':fontsize=24:fontcolor=0x59667A:x=100:y=196[g4]")
    # 画面下方标识（覆盖裁切后的合规标识位）
    f.append(f"[g4]drawtext=fontfile='{FONT}':text='AI 生成形象 · 演示用素材':fontsize=22:fontcolor=0x4B5768:x={AX}:y={AY+AH+18}[g5]")
    # 分镜刻度条（11 段，当前段高亮）
    f.append(f"[g5]drawbox=x=100:y=992:w=900:h=6:color=0x1B2430:t=fill[g6]")
    cur = "[g6]"
    seg_w = 900 / max(len(plan), 1)
    for i, (path, a, b, kind) in enumerate(caps):
        lab = f"[t{i}]"
        f.append(f"{cur}drawbox=x={100 + i*seg_w + 1:.1f}:y=992:w={seg_w - 3:.1f}:h=6:color=0x3B82F6:t=fill:"
                 f"enable='between(t,{a:.2f},{b:.2f})'{lab}")
        cur = lab
    for i, (path, a, b, kind) in enumerate(caps):
        lab = f"[c{i}]"
        color = "0xF2F6FC" if kind == "talk" else "0x8A97AA"
        size = 56 if kind == "talk" else 40
        safe = path.replace("\\", "/")
        f.append(f"{cur}drawtext=fontfile='{FONT_BD}':textfile='{safe}':fontsize={size}:fontcolor={color}:"
                 f"line_spacing=-30:x=100:y=372:enable='between(t,{a:.2f},{b:.2f})'{lab}")
        cur = lab
    # status chip
    talk_iv = "+".join(f"between(t,{a:.2f},{b:.2f})" for path, a, b, k in caps if k == "talk")
    hold_iv = "+".join(f"between(t,{a:.2f},{b:.2f})" for path, a, b, k in caps if k == "hold")
    f.append(f"{cur}drawtext=fontfile='{FONT}':text='● 说话中':fontsize=26:fontcolor=0x34D399:x=100:y=930:"
             f"enable='{talk_iv}'[s1]")
    f.append(f"[s1]drawtext=fontfile='{FONT}':text='○ 倾听中':fontsize=26:fontcolor=0x60A5FA:x=100:y=930:"
             f"enable='{hold_iv}'[s2]")
    f.append(f"[s2]drawtext=fontfile='{FONT}':text='素材：2×10s 即梦片段 · 状态机拼接 · TTS 对齐':fontsize=22:fontcolor=0x414E60:x=100:y=1024[s3]")
    fc = ";".join(f)
    out = os.path.join(OUT, "nova_interview_demo_1080p.mp4")
    run([FF, "-hide_banner", "-loglevel", "error", "-i", avatar, "-i", audio,
         "-filter_complex", fc, "-map", "[s3]", "-map", "1:a",
         "-t", f"{total:.3f}", "-c:v", "libx264", "-crf", "18", "-preset", "medium",
         "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
         "-movflags", "+faststart", "-y", out])
    return out

if __name__ == "__main__":
    stage = sys.argv[1] if len(sys.argv) > 1 else "all"
    if stage in ("tts", "all"):
        tts = stage_tts(); print("TTS:", json.dumps(tts, ensure_ascii=False))
    else:
        tts = {n: dur(os.path.join(TTS, n + ".wav")) for n, _ in LINES}
    if stage in ("seg", "all"):
        stage_segments()
    else:
        for k in NAMES:
            SEG[k] = {"path": os.path.join(CLIPS, k + ".mp4"), "dur": dur(os.path.join(CLIPS, k + ".mp4"))}
    if stage in ("shots", "all"):
        plan = stage_shots(tts)
    else:
        plan = json.load(open(os.path.join(CLIPS, "plan.json"), encoding="utf-8"))
    print("PLAN:", json.dumps([(p["id"], p["kind"], p["dur"]) for p in plan], ensure_ascii=False))
    if stage in ("concat", "all"):
        avatar, starts = stage_concat(plan)
    else:
        total_pre = sum(p["dur"] for p in plan) - (len(plan) - 1) * XF
        starts, acc = [0.0], 0.0
        for i, p in enumerate(plan):
            if i: starts.append(acc + XF)
            acc += p["dur"] - XF if i < len(plan) - 1 else p["dur"]
        avatar = os.path.join(CLIPS, "avatar_seq.mp4")
    total = dur(avatar)
    print("VIDEO TOTAL:", round(total, 3))
    if stage in ("audio", "all"):
        audio = stage_audio(plan, starts, tts, total)
    else:
        audio = os.path.join(CLIPS, "bed.wav")
    if stage in ("ui", "all"):
        out = stage_ui(plan, starts, avatar, audio, total)
        print("OUT:", out)










