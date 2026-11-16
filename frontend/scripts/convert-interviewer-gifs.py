"""把面试官数字人的三个动图转换成 mp4 + poster.webp。

用法（在 frontend 目录下）：

    python scripts/convert-interviewer-gifs.py
    python scripts/convert-interviewer-gifs.py "D:/some/other/folder"

需要 Pillow 和 imageio-ffmpeg（后者自带 ffmpeg 可执行文件，不用另外装）：

    pip install pillow imageio-ffmpeg

三张动图 → 三个视频，一比一：帧序原样保留，不挑帧、不倒放、不混合、不裁剪。
输出时长和原动图完全一致（帧数 × 0.4s）。

唯一的技术处理是把 2.5fps 的帧序列重复到 14fps 容器里再编码——时间轴不变，
只是避免超低帧率的 H.264 在部分播放器里因时间戳取整而抖。视觉上没有任何差别。
"""

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageSequence

OUT = Path(__file__).resolve().parents[1] / "public" / "interviewer"
WORK = Path(tempfile.gettempdir()) / "panel-interviewer-frames"

SIZE = 640
# 15 能整除 0.4s（=6 帧），所以每帧时长精确等于源动图，不会整体变慢
OUT_FPS = 15
# 源动图是 2.5fps；每格占 1/2.5 = 0.4 秒
SLOT_SECONDS = 1.0 / 2.5

# (输出名, 源文件名, 是否用它生成 poster)
CLIPS = [
    ("idle", "办公室女性表情动画九宫格.gif", True),
    ("listening", "AI_Interviewer_listening.gif", False),
    ("speaking", "AI_Interviewer_speaking.gif", False),
]


def ffmpeg_binary() -> str:
    try:
        import imageio_ffmpeg
    except ImportError:
        raise SystemExit("缺少 imageio-ffmpeg，先跑：pip install pillow imageio-ffmpeg")
    return imageio_ffmpeg.get_ffmpeg_exe()


def read_frames(path: Path):
    with Image.open(path) as image:
        return [frame.convert("RGB").copy() for frame in ImageSequence.Iterator(image)]


def _downscale(array: np.ndarray, factor: int) -> np.ndarray:
    height = array.shape[0] // factor * factor
    width = array.shape[1] // factor * factor
    view = array[:height, :width].reshape(height // factor, factor, width // factor, factor)
    return view.mean(axis=(1, 3))


def _search(reference: np.ndarray, frame: np.ndarray, limit: int) -> tuple[int, int]:
    height, width = reference.shape
    inner = (slice(height // 6, height - height // 6), slice(width // 6, width - width // 6))
    best, best_score = (0, 0), None
    for dy in range(-limit, limit + 1):
        for dx in range(-limit, limit + 1):
            candidate = np.roll(np.roll(frame, dy, axis=0), dx, axis=1)
            score = float(np.abs(candidate[inner] - reference[inner]).mean())
            if best_score is None or score < best_score:
                best_score, best = score, (dy, dx)
    return best


def estimate_offset(reference: np.ndarray, frame: np.ndarray):
    """两级搜索：先在三倍降采样上搜 ±30，再回到原图 ±2 精修。

    源图每帧都是同一场景，但生成时头部位置会漂到十几个像素；直接放大到全屏后
    这点漂移就是肉眼可见的"抽动"，所以先对齐再编码。
    """
    coarse_ref = _downscale(reference, 3)
    coarse_frame = _downscale(frame, 3)
    rough_dy, rough_dx = _search(coarse_ref, coarse_frame, 10)
    rough_dy *= 3
    rough_dx *= 3

    best, best_score = (rough_dy, rough_dx), None
    height, width = reference.shape
    inner = (slice(height // 6, height - height // 6), slice(width // 6, width - width // 6))
    for dy in range(rough_dy - 2, rough_dy + 3):
        for dx in range(rough_dx - 2, rough_dx + 3):
            candidate = np.roll(np.roll(frame, dy, axis=0), dx, axis=1)
            score = float(np.abs(candidate[inner] - reference[inner]).mean())
            if best_score is None or score < best_score:
                best_score, best = score, (dy, dx)
    return best


def align(frames):
    """把所有帧平移对齐到第一帧，然后统一裁掉可能出现空边的边界。"""
    grays = [np.asarray(frame.convert("L"), dtype=np.float32) for frame in frames]
    reference = grays[0]

    offsets = [estimate_offset(reference, gray) for gray in grays]
    margin = max(max(abs(dy), abs(dx)) for dy, dx in offsets) + 1

    aligned = []
    for frame, (dy, dx) in zip(frames, offsets):
        array = np.asarray(frame, dtype=np.uint8)
        shifted = np.roll(np.roll(array, dy, axis=0), dx, axis=1)
        aligned.append(Image.fromarray(shifted[margin:-margin, margin:-margin]))
    return aligned, offsets


def hold_frames(sequence):
    """帧序不动，只是每帧重复若干次把 0.4s 的时长填满。"""
    repeats = max(1, round(SLOT_SECONDS * OUT_FPS))
    produced = []
    for frame in sequence:
        produced.extend([np.asarray(frame, dtype=np.uint8)] * repeats)
    return produced


def build(ffmpeg: str, clip: str, frames, poster: bool):
    work = WORK / clip
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)

    aligned, offsets = align(frames)
    sequence = [frame.resize((SIZE, SIZE), Image.LANCZOS) for frame in aligned]
    produced = hold_frames(sequence)

    for index, array in enumerate(produced):
        Image.fromarray(array).save(work / f"{index:04d}.png")

    target = OUT / f"{clip}.mp4"
    subprocess.run(
        [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-framerate", str(OUT_FPS),
            "-i", str(work / "%04d.png"),
            "-c:v", "libx264", "-profile:v", "high", "-crf", "21",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an",
            str(target),
        ],
        check=True,
    )

    if poster:
        sequence[0].save(OUT / "poster.webp", quality=86, method=6)

    print(f"{clip:10s} {len(frames)} 帧 -> {len(produced)} 帧, "
          f"{len(produced) / OUT_FPS:.2f}s, {target.stat().st_size / 1024:.0f} KB, "
          f"对齐位移={offsets}")


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads"
    if not source.is_dir():
        raise SystemExit(f"找不到动图目录：{source}")

    OUT.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    ffmpeg = ffmpeg_binary()

    for clip, filename, poster in CLIPS:
        path = source / filename
        if not path.exists():
            raise SystemExit(f"缺少源文件：{path}")
        build(ffmpeg, clip, read_frames(path), poster)

    shutil.rmtree(WORK, ignore_errors=True)
    print(f"完成，输出目录：{OUT}")


if __name__ == "__main__":
    main()
