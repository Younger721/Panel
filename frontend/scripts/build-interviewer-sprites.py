"""生成面试官那张静态人像（base.webp）。

用法（在 frontend 目录下）：

    python scripts/build-interviewer-sprites.py

素材是 `public/interviewer/idle.webp`：闭嘴、微笑、不露齿的正脸抠图。

要做的只有一件事——清掉抠图白边。rembg 抠出来的 alpha 边缘那一圈像素还留着
办公室的浅色背景，直接放在虚化背景上，头发外沿会有一圈像贴纸的浅色描边。

输出到 public/interviewer/：

    base.webp   640×640，带 alpha 的静态人像
"""

from pathlib import Path

import cv2
import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parents[1] / "public" / "interviewer"
SOURCE = OUT / "idle.webp"
TARGET = OUT / "base.webp"


def clean_matte(rgb: np.ndarray, alpha: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """去掉抠图白边，再把硬边羽化。

    先把边缘像素的颜色往外铺（用邻近不透明像素的颜色），再收边 + 羽化。
    """
    # 只拿「确定是人物」的内核像素当颜色源：原 alpha 边缘那一圈本身就掺了白背景，
    # 用它当种子会把白边一起铺出去。
    core = cv2.erode((alpha > 200).astype(np.uint8), np.ones((5, 5), np.uint8))
    known = core.astype(np.float32)
    colors = rgb.astype(np.float32)
    for _ in range(8):
        weight = cv2.GaussianBlur(known, (0, 0), 1.6)
        spread = cv2.GaussianBlur(colors * known[..., None], (0, 0), 1.6)
        filled = spread / np.maximum(weight, 1e-6)[..., None]
        colors = np.where((known > 0.5)[..., None], colors, filled)
        known = np.maximum(known, (weight > 0.02).astype(np.float32))
    cleaned = np.clip(colors, 0, 255).astype(np.uint8)

    soft = alpha.astype(np.float32)
    soft = cv2.erode(soft, np.ones((7, 7), np.uint8))
    soft = cv2.GaussianBlur(soft, (0, 0), 1.2)
    return cleaned, np.clip(soft, 0, 255).astype(np.uint8)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"缺少原始抠图：{SOURCE}")
    rgba = np.array(Image.open(SOURCE).convert("RGBA"))

    rgb, alpha = clean_matte(rgba[:, :, :3], rgba[:, :, 3])

    Image.fromarray(np.dstack([rgb, alpha]), "RGBA").save(
        TARGET, quality=92, method=6
    )
    print(f"写入 {TARGET}  {TARGET.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
