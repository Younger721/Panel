# -*- coding: utf-8 -*-
"""把 room-sharp.webp 处理成虚化的 room.webp。

为什么不是直接换一张图：背景里那几个人坐在沙发上，很抢戏。
这里只做「景深式虚化 + 轻微去饱和」，保留办公室的空间感，又不会跟人物抢视线。

参数是同一条曲线上的：BLUR 按 1920 宽下的像素算，视口缩到 1440 时等效约 16px。
最后加一层 sigma=1.6 的抖动噪点，避免大面积柔渐变在屏幕上出现色带。
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

ASSETS = Path(__file__).resolve().parents[1] / "public" / "interviewer"
SOURCE = ASSETS / "room-sharp.webp"
TARGET = ASSETS / "room.webp"

BLUR = 18
SATURATION = 0.82
BRIGHTNESS = 1.02
CONTRAST = 1.08
NOISE_SIGMA = 1.6


def main() -> None:
    image = Image.open(SOURCE).convert("RGB")
    image = image.filter(ImageFilter.GaussianBlur(BLUR))
    image = ImageEnhance.Color(image).enhance(SATURATION)
    image = ImageEnhance.Brightness(image).enhance(BRIGHTNESS)
    image = ImageEnhance.Contrast(image).enhance(CONTRAST)

    # 抖动噪点：只为了打散渐变色带，幅度控制在 1~2 级，肉眼看不出颗粒
    pixels = np.asarray(image).astype(np.float32)
    rng = np.random.default_rng(7)
    pixels += rng.normal(0.0, NOISE_SIGMA, pixels.shape[:2])[..., None]
    image = Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8))

    image.save(TARGET, "WEBP", quality=90, method=6)
    print(f"{TARGET.name}: {image.size[0]}x{image.size[1]}, {TARGET.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
