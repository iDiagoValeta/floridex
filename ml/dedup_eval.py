"""Drop held-out garden photos (data/raw/inat_eval) that are near duplicates of a
training photo of the same class: the same plant is often uploaded in several
observations, and a copy in training would inflate the score.

    python dedup_eval.py
"""
import subprocess
from pathlib import Path

import imagehash
import numpy as np
from PIL import Image

DUP_DISTANCE = 4


def h(p):
    return int(str(imagehash.phash(Image.open(p))), 16)


fuera = 0
for carpeta in sorted(Path("data/raw/inat_eval").iterdir()):
    if not carpeta.is_dir():
        continue
    train = np.array([h(p) for p in (Path("data/raw/inat_api") / carpeta.name).glob("*.jpg")], dtype=np.uint64)
    for f in sorted(carpeta.glob("*.jpg")):
        try:
            x = np.bitwise_xor(train, np.uint64(h(f)))
        except Exception:
            continue
        if len(train) and np.unpackbits(x.view(np.uint8).reshape(-1, 8), axis=1).sum(1).min() <= DUP_DISTANCE:
            subprocess.run(["gio", "trash", str(f)], check=True)
            fuera += 1
print(f"{fuera} held-out photos were near duplicates of a training photo; moved to the trash")
