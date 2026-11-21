"""Add a "no flower" class so the apps can say when a photo shows no flower.

A closed-set classifier always names one of its flowers: a photo of a living
room came out as "Rosa". Negatives come from three public sets plus the
Swedish "Ålgräs" photos, which only show leaves. Scene classes where flowers
are likely to appear (gardens, greenhouses, florists, meadows...) are left out
so the class does not learn to reject real flowers.

Run after prepare.py. Appends to data/manifest.jsonl and data/classes.json.
"""
import io
import json
import random
from pathlib import Path

import pyarrow.parquet as pq
from PIL import Image
from tqdm import tqdm

from prepare import RAW, parquet_rows, save, stratified

NEG = RAW / "neg"
OUT = Path("data/img/neg")
KEY = "no flower"
rng = random.Random(29)

CALTECH_EXCLUDED = {"sunflower", "water_lilly", "lotus", "bonsai", "strawberry", "BACKGROUND_Google"}
SUN_EXCLUDED = {
    "botanical garden", "cottage garden", "formal garden", "herb garden", "topiary garden", "vegetable garden",
    "greenhouse indoor", "greenhouse outdoor", "florist shop indoor", "field wild", "hayfield", "pasture",
    "yard", "patio", "restaurant patio", "courtyard", "balcony exterior", "gazebo exterior", "park",
    "marsh", "swamp", "bayou", "cemetery", "orchard", "market outdoor", "market indoor", "general store outdoor",
    "rainforest", "forest path", "forest road", "forest broadleaf", "valley",
}


def sun_rows(path):
    t = pq.read_table(path)
    names = json.loads(t.schema.metadata[b"huggingface"])["info"]["features"]["label"]["names"]
    for r in t.to_pylist():
        if names[r["label"]] not in SUN_EXCLUDED:
            yield Image.open(io.BytesIO(r["image"]["bytes"])), names[r["label"]]


def main():
    rows = []

    def add(items, src, split):
        for j, (img, raw) in enumerate(tqdm(items, desc=f"{src} {split}")):
            p = save(img, OUT / src / split / f"{j}.jpg")
            rows.append(dict(path=p, dataset="no_flor", split=split, label_raw=f"{src}:{raw}", key=KEY))

    # Imagenette: its own train/val folders; val is split into our val and test.
    tr = [f for f in sorted((NEG / "imagenette2-320/train").glob("*/*.JPEG"))]
    va = [f for f in sorted((NEG / "imagenette2-320/val").glob("*/*.JPEG"))]
    rng.shuffle(tr)
    rng.shuffle(va)
    add(((Image.open(f), f.parent.name) for f in tr[:3000]), "imagenette", "train")
    add(((Image.open(f), f.parent.name) for f in va[:300]), "imagenette", "val")
    add(((Image.open(f), f.parent.name) for f in va[300:1300]), "imagenette", "test")

    # Caltech-101 without its flower-like categories.
    cal = [(f, f.parent.name) for f in sorted((NEG / "101_ObjectCategories").glob("*/*.jpg")) if f.parent.name not in CALTECH_EXCLUDED]
    for split, part in zip(("train", "val", "test"), stratified(cal, [0.6, 0.1, 0.3])):
        add(((Image.open(f), c) for f, c in part), "caltech101", split)

    # SUN397 scenes: two train shards for train/val, one test shard for test.
    sun_train = list(sun_rows(NEG / "sun-train-00000.parquet")) + list(sun_rows(NEG / "sun-train-00009.parquet"))
    rng.shuffle(sun_train)
    cut = int(len(sun_train) * 0.9)
    add(sun_train[:cut], "sun397", "train")
    add(sun_train[cut:], "sun397", "val")
    add(list(sun_rows(NEG / "sun-test-00004.parquet")), "sun397", "test")

    # Ålgräs: leaves on the forest floor, no flower in sight.
    for split, fname in (("train", "train"), ("val", "validation"), ("test", "test")):
        add([(img, n) for img, n in parquet_rows(RAW / f"sw/{fname}.parquet") if n == "Ålgräs"], "swedish_algras", split)

    with open("data/manifest.jsonl", "a") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    classes = json.loads(Path("data/classes.json").read_text())
    if KEY not in classes:
        Path("data/classes.json").write_text(json.dumps(sorted(classes + [KEY]), ensure_ascii=False, indent=1))
    for s in ("train", "val", "test"):
        print(s, sum(r["split"] == s for r in rows))


if __name__ == "__main__":
    main()
