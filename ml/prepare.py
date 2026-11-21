"""Build one manifest over four public flower datasets.

Images are re-encoded with their short side at 288 px, labels are mapped to the
unified taxonomy, and any train/val image that is a near duplicate of a test
image (perceptual hash) is dropped so no test image leaks into training.
"""
import io
import json
import random
from collections import defaultdict
from pathlib import Path

import imagehash
import numpy as np
import pyarrow.parquet as pq
from PIL import Image
from scipy.io import loadmat
from tqdm import tqdm

from taxonomy import OXFORD17, OXFORD102, SWEDISH, TF_FLOWERS

RAW = Path("data/raw")
OUT = Path("data/img")
SHORT_SIDE = 288
DUP_DISTANCE = 4
# Ålgräs only shows leaves on the forest floor, never a flower.
SWEDISH_EXCLUDED = {"Ålgräs"}
rng = random.Random(23)


def save(img: Image.Image, path: Path) -> str:
    img = img.convert("RGB")
    w, h = img.size
    s = SHORT_SIDE / min(w, h)
    if s < 1:
        img = img.resize((round(w * s), round(h * s)), Image.BICUBIC)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, quality=92)
    return str(path)


def stratified(items, fractions):
    """Split (x, label) items into len(fractions) parts per label."""
    by = defaultdict(list)
    for it in items:
        by[it[1]].append(it)
    parts = [[] for _ in fractions]
    for group in by.values():
        rng.shuffle(group)
        n, start = len(group), 0
        for i, f in enumerate(fractions):
            end = n if i == len(fractions) - 1 else start + round(n * f)
            parts[i] += group[start:end]
            start = end
    return parts


def parquet_rows(path):
    t = pq.read_table(path)
    names = json.loads(t.schema.metadata[b"huggingface"])["info"]["features"]["label"]["names"]
    for r in t.to_pylist():
        yield Image.open(io.BytesIO(r["image"]["bytes"])), names[r["label"]]


def main():
    rows = []  # dict(path, dataset, split, label_raw, key)

    # Oxford 102, official split.
    labels = loadmat(RAW / "imagelabels.mat")["labels"][0]
    setid = loadmat(RAW / "setid.mat")
    for split, field in [("train", "trnid"), ("val", "valid"), ("test", "tstid")]:
        for i in tqdm(setid[field][0], desc=f"o102 {split}"):
            name = OXFORD102[labels[i - 1] - 1]
            p = save(Image.open(RAW / "jpg" / f"image_{i:05d}.jpg"), OUT / "o102" / split / f"{i}.jpg")
            rows.append(dict(path=p, dataset="oxford102", split=split, label_raw=name, key=name))

    # Oxford 17, published 80/20 split; 10% of train held out for validation.
    o17_train = list(parquet_rows(RAW / "o17/train.parquet"))
    tr, va = stratified(o17_train, [0.9, 0.1])
    for split, items in [("train", tr), ("val", va), ("test", list(parquet_rows(RAW / "o17/test.parquet")))]:
        for j, (img, name) in enumerate(tqdm(items, desc=f"o17 {split}")):
            p = save(img, OUT / "o17" / split / f"{j}.jpg")
            rows.append(dict(path=p, dataset="oxford17", split=split, label_raw=name, key=OXFORD17[name]))

    # tf_flowers has no split: stratified 70/10/20 with a fixed seed.
    tf_items = [(f, f.parent.name) for f in sorted((RAW / "flower_photos").glob("*/*.jpg"))]
    for split, items in zip(["train", "val", "test"], stratified(tf_items, [0.7, 0.1, 0.2])):
        for f, name in tqdm(items, desc=f"tf {split}"):
            p = save(Image.open(f), OUT / "tf" / split / f"{name}_{f.stem}.jpg")
            rows.append(dict(path=p, dataset="tf_flowers", split=split, label_raw=name, key=TF_FLOWERS[name]))

    # Swedish Flowers, published split.
    for split, fname in [("train", "train"), ("val", "validation"), ("test", "test")]:
        for j, (img, name) in enumerate(tqdm(parquet_rows(RAW / f"sw/{fname}.parquet"), desc=f"sw {split}")):
            if name in SWEDISH_EXCLUDED:
                continue
            p = save(img, OUT / "sw" / split / f"{j}.jpg")
            rows.append(dict(path=p, dataset="swedish", split=split, label_raw=name, key=SWEDISH[name]))

    # Near-duplicate removal: no train/val image may resemble any test image.
    hashes = np.array([int(str(imagehash.phash(Image.open(r["path"]))), 16) for r in tqdm(rows, desc="phash")], dtype=np.uint64)
    test_idx = np.array([i for i, r in enumerate(rows) if r["split"] == "test"])
    test_h = hashes[test_idx]
    drop = set()
    for i, r in enumerate(rows):
        if r["split"] == "test":
            continue
        x = np.bitwise_xor(test_h, hashes[i])
        dist = np.unpackbits(x.view(np.uint8).reshape(-1, 8), axis=1).sum(1)
        if dist.min() <= DUP_DISTANCE:
            drop.add(i)
    print(f"dropped {len(drop)} train/val near-duplicates of test images")
    rows = [r for i, r in enumerate(rows) if i not in drop]

    classes = sorted({r["key"] for r in rows})
    Path("data/classes.json").write_text(json.dumps(classes, ensure_ascii=False, indent=1))
    with open("data/manifest.jsonl", "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    stats = defaultdict(int)
    for r in rows:
        stats[(r["dataset"], r["split"])] += 1
    for k in sorted(stats):
        print(k, stats[k])
    print("classes:", len(classes))


if __name__ == "__main__":
    main()
