"""Expanded taxonomy (v2): the 182 flowers of v1 plus wild and garden species
from two harder, real-world public benchmarks.

- iNaturalist North American Wildflowers 75 (meganariley/inat-wildflowers-75):
  research-grade field photos. Its validation split is our test benchmark; its
  train split is split 90/10 into our train/val.
- Pl@ntNet-300K (NeurIPS 2021, label ambiguity and long tail): only photos whose
  organ is "flower", only species with at least 30 such training photos and 3
  test photos. Official train/val/test splits; train capped per class so the
  head species do not swamp the rest.

Species are merged by binomial name across datasets (PlantNet lists some species
twice with different authorities). Merges into v1 classes are explicit below.
Every new train/val photo that is a near duplicate of any test photo (old or
new benchmark) is dropped.

Run after prepare.py and prepare_neg.py. Writes data/manifest2.jsonl,
data/classes2.json and data/taxonomia2.json (key -> scientific name, sources,
English common name) for the Spanish cards.
"""
import io
import json
import random
import re
from collections import Counter, defaultdict
from multiprocessing import Pool
from pathlib import Path

import imagehash
import numpy as np
import pyarrow.parquet as pq
from PIL import Image
from tqdm import tqdm

RAW = Path("data/raw")
OUT = Path("data/img2")
SHORT_SIDE = 288
DUP_DISTANCE = 4
PN_MIN_TRAIN, PN_MIN_TEST = 30, 3
CAP = {"plantnet": (300, 40), "inat75": (400, None)}  # train, val caps per class
rng = random.Random(41)

# Explicit merges into v1 classes (binomial -> v1 key). Same-species matches are
# found automatically from the v1 cards' scientific names.
MERGE = {
    ("Gaillardia", "pulchella"): "blanket flower",
    ("Rosa", "blanda"): "dog rose",
    ("Tagetes", "patula"): "marigold",
    ("Tagetes", "erecta"): "marigold",
    ("Tagetes", "tenuifolia"): "marigold",
    ("Pelargonium", "zonale"): "geranium",
    ("Pelargonium", "inquinans"): "geranium",
    ("Pelargonium", "hybridum"): "geranium",
    ("Pelargonium", "hortorum"): "geranium",
    ("Pelargonium", "peltatum"): "pelargonium",
    ("Pelargonium", "graveolens"): "pelargonium",
    # Synonyms: same species under another name.
    ("Anemone", "hepatica"): "liverleaf",  # = Hepatica nobilis
}


def binom(s):
    s = re.sub(r"[‘’'\"*]", "", s).replace("×", " x ")
    w = s.split()
    if len(w) >= 3 and w[1] == "x":
        return (w[0].capitalize(), w[2].lower())
    if len(w) >= 2 and w[1][0].islower() and w[1] not in ("spp.", "sp.", "spp"):
        return (w[0].capitalize(), w[1].lower())
    return (w[0].capitalize(), None)


def guardar(args):
    datos, destino = args
    img = Image.open(io.BytesIO(datos)).convert("RGB")
    w, h = img.size
    s = SHORT_SIDE / min(w, h)
    if s < 1:
        img = img.resize((round(w * s), round(h * s)), Image.BICUBIC)
    destino.parent.mkdir(parents=True, exist_ok=True)
    img.save(destino, quality=92)
    return int(str(imagehash.phash(img)), 16)


def main():
    v1 = [json.loads(l) for l in open("data/manifest.jsonl")]
    cards = {c["key"]: c for c in json.load(open("../shared/flores.json"))}
    v1_by_sp = {binom(c["cientifico"]): k for k, c in cards.items() if binom(c["cientifico"])[1]}

    def clave(b):
        return MERGE.get(b) or v1_by_sp.get(b) or f"{b[0]} {b[1]}"

    tax = {}  # key -> info for the cards
    cand = []  # (file, row, key, dataset, split, label_raw): bytes are read later, file by file

    # iNat 75
    tabla = [l for l in open(RAW / "inat75/README.md") if re.match(r"^\| *\d+ *\|", l)]
    inat_b = {}
    for l in tabla:
        p = [c.strip() for c in l.strip().strip("|").split("|")]
        slug = re.sub(r"[^a-z0-9]+", "_", p[1].lower().replace("'", "")).strip("_")
        inat_b[slug] = (binom(p[2]), p[1])
    for parte, split in (("train", "train"), ("validation", "test")):
        for f in sorted((RAW / "inat75").glob(f"{parte}-*.parquet")):
            t = pq.read_table(f, columns=["label"])
            names = json.loads(t.schema.metadata[b"huggingface"])["info"]["features"]["label"]["names"]
            for i, lab in enumerate(t.column("label").to_pylist()):
                b, comun = inat_b[names[lab]]
                k = clave(b)
                tax.setdefault(k, {"cientifico": f"{b[0]} {b[1]}", "comun_en": comun, "fuentes": set()})["fuentes"].add("inat75")
                cand.append((f, i, k, "inat75", split, names[lab]))

    # Pl@ntNet-300K, flower photos only
    pn_names = json.load(open(RAW / "plantnet/plantnet300K_species_id_2_name.json"))
    meta = json.load(open(RAW / "plantnet/plantnet300K_metadata.json"))
    c = Counter((v["species_id"], v["split"]) for v in meta.values() if v["organ"] == "flower")
    elegidas = {s for s in pn_names if c[(s, "train")] >= PN_MIN_TRAIN and c[(s, "test")] >= PN_MIN_TEST}
    for parte in ("train", "val", "test"):
        for f in sorted((RAW / "plantnet").glob(f"{parte}-*.parquet")):
            t = pq.read_table(f, columns=["label", "image.path"])
            labels = json.loads(t.schema.metadata[b"huggingface"])["info"]["features"]["label"]["names"]
            for i, (lab, path) in enumerate(zip(t.column("label").to_pylist(), t.column("path").to_pylist())):
                sid = labels[lab]
                m = meta.get(path.rsplit(".", 1)[0])
                if sid not in elegidas or not m or m["organ"] != "flower":
                    continue
                b = binom(pn_names[sid])
                k = clave(b)
                tax.setdefault(k, {"cientifico": f"{b[0]} {b[1]}", "comun_en": None, "fuentes": set()})["fuentes"].add("plantnet")
                cand.append((f, i, k, "plantnet", parte, pn_names[sid]))
    del meta

    # iNat train -> train/val 90/10; caps per class.
    por = defaultdict(list)
    for n in cand:
        por[(n[3], n[4], n[2])].append(n)
    elegidos = []
    for (ds, split, _k), items in por.items():
        rng.shuffle(items)
        if ds == "inat75" and split == "train":
            corte = max(1, len(items) // 10)
            elegidos += [(n, "val") for n in items[:min(corte, 60)]]
            elegidos += [(n, "train") for n in items[corte:][:CAP["inat75"][0]]]
        elif split == "train":
            elegidos += [(n, "train") for n in items[:CAP[ds][0]]]
        elif split == "val":
            elegidos += [(n, "val") for n in items[:CAP[ds][1]]]
        else:
            elegidos += [(n, "test") for n in items]
    del cand, por

    # Decode and save, one parquet file at a time.
    por_fichero = defaultdict(list)
    for j, (n, split) in enumerate(elegidos):
        por_fichero[n[0]].append((j, n, split))
    filas, hashes = [None] * len(elegidos), [None] * len(elegidos)
    with Pool(14) as pool:
        for f, items in tqdm(sorted(por_fichero.items(), key=lambda x: str(x[0])), desc="ficheros"):
            col = pq.read_table(f, columns=["image"]).column("image")
            trabajos = [(col[n[1]].as_py()["bytes"], OUT / n[3] / split / f"{j}.jpg") for j, n, split in items]
            for (j, n, split), h, (_, destino) in zip(items, pool.map(guardar, trabajos, chunksize=32), trabajos):
                hashes[j] = h
                filas[j] = dict(path=str(destino), dataset=n[3], split=split, label_raw=n[5], key=n[2])
            del col, trabajos

    # Near duplicates against every test photo, old and new.
    v1_test = [r for r in v1 if r["split"] == "test"]
    with Pool(14) as pool:
        h_v1 = list(tqdm(pool.imap(_phash, [r["path"] for r in v1_test], chunksize=64), total=len(v1_test), desc="phash v1"))
    test_h = np.array(h_v1 + [h for h, f in zip(hashes, filas) if f["split"] == "test"], dtype=np.uint64)
    fuera = set()
    for i, (h, f) in enumerate(zip(hashes, filas)):
        if f["split"] == "test":
            continue
        x = np.bitwise_xor(test_h, np.uint64(h))
        if np.unpackbits(x.view(np.uint8).reshape(-1, 8), axis=1).sum(1).min() <= DUP_DISTANCE:
            fuera.add(i)
    filas = [f for i, f in enumerate(filas) if i not in fuera]
    print(f"dropped {len(fuera)} train/val photos that are near duplicates of a test photo")

    # v1 rows: Oxford 102's val joins train (standard for its final protocol).
    for r in v1:
        if r["dataset"] == "oxford102" and r["split"] == "val":
            r = {**r, "split": "train"}
        filas.append(r)
    clases = sorted({f["key"] for f in filas})
    Path("data/classes2.json").write_text(json.dumps(clases, ensure_ascii=False, indent=1))
    with open("data/manifest2.jsonl", "w") as fh:
        for f in filas:
            fh.write(json.dumps(f, ensure_ascii=False) + "\n")
    for k in clases:
        if k in cards:
            tax.setdefault(k, {"cientifico": cards[k]["cientifico"], "comun_en": None, "fuentes": set()})["v1"] = True
    Path("data/taxonomia2.json").write_text(json.dumps(
        {k: {**v, "fuentes": sorted(v["fuentes"])} for k, v in sorted(tax.items())}, ensure_ascii=False, indent=1))
    st = Counter((f["dataset"], f["split"]) for f in filas)
    for k in sorted(st):
        print(k, st[k])
    print("classes:", len(clases), "new:", sum(1 for k in clases if k not in cards and k != "no flower"))


def _phash(p):
    return int(str(imagehash.phash(Image.open(p))), 16)


if __name__ == "__main__":
    main()
