"""Append the iNaturalist API photos (fetch_inat.py) to the v2 manifest as
training data, after dropping near duplicates of any test photo.

    python prepare_extra.py
"""
import json
from multiprocessing import Pool
from pathlib import Path

import imagehash
import numpy as np
from PIL import Image
from tqdm import tqdm

SRC = Path("data/raw/inat_api")
OUT = Path("data/img2/inat_api")
SHORT_SIDE = 288
DUP_DISTANCE = 4


def guardar(args):
    origen, destino = args
    try:
        img = Image.open(origen).convert("RGB")
    except Exception:
        return None
    w, h = img.size
    s = SHORT_SIDE / min(w, h)
    if s < 1:
        img = img.resize((round(w * s), round(h * s)), Image.BICUBIC)
    destino.parent.mkdir(parents=True, exist_ok=True)
    img.save(destino, quality=92)
    return int(str(imagehash.phash(img)), 16)


def _phash(p):
    return int(str(imagehash.phash(Image.open(p))), 16)


def main():
    rows = [json.loads(l) for l in open("data/manifest2.jsonl")]
    rows = [r for r in rows if r["dataset"] != "inat_api"]  # idempotent
    clases = set(json.load(open("data/classes2.json")))
    trabajos, filas = [], []
    for carpeta in sorted(p for p in SRC.iterdir() if p.is_dir()):
        if carpeta.name not in clases:
            print("unknown class:", carpeta.name)
            continue
        for f in sorted(carpeta.glob("*.jpg")):
            destino = OUT / carpeta.name.replace(" ", "_") / f.name
            trabajos.append((f, destino))
            filas.append(dict(path=str(destino), dataset="inat_api", split="train", label_raw=carpeta.name, key=carpeta.name))
    test = [r["path"] for r in rows if r["split"] == "test"]
    with Pool(14) as pool:
        hashes = list(tqdm(pool.imap(guardar, trabajos, chunksize=32), total=len(trabajos), desc="save"))
        test_h = np.array(list(tqdm(pool.imap(_phash, test, chunksize=64), total=len(test), desc="phash test")), dtype=np.uint64)
    nuevas, fuera = [], 0
    for h, f in zip(hashes, filas):
        if h is None:
            continue
        x = np.bitwise_xor(test_h, np.uint64(h))
        if np.unpackbits(x.view(np.uint8).reshape(-1, 8), axis=1).sum(1).min() <= DUP_DISTANCE:
            fuera += 1
            continue
        nuevas.append(f)
    with open("data/manifest2.jsonl", "w") as fh:
        for r in rows + nuevas:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"added {len(nuevas)} iNaturalist photos; dropped {fuera} near duplicates of a test photo")


if __name__ == "__main__":
    main()
