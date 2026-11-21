"""One thumbnail per class for the catalogue and the "is it one of these?"
choices. Among up to 80 training photos of the class, it picks the one with
the best product of the model's confidence and how much of the photo's centre
looks like petals (saturated non-green or bright white pixels). Confidence
alone picked meadows, leaves and people where the flower was a speck, because
the model also reads the surroundings. The thumbnail is the central 80 %.

    python miniaturas.py <model.onnx> <out dir> [<manifest.jsonl> | <photos dir>]
Writes <out>/<slug>.webp (240 px, square) where slug is the class key with
spaces and punctuation replaced by "-". Candidates come from the training split
of the manifest, or from <photos dir>/<class>/*.jpg as fetch_inat.py writes
them; then <out>/creditos.json also gets each chosen photo's author, licence
and observation, from <photos dir>/creditos.jsonl.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from PIL import Image
from torchvision import transforms as T

from train import MEAN, STD

modelo, out = sys.argv[1], Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
labels = json.loads(Path(modelo).parent.parent.joinpath("config.json").read_text())["id2label"]
idx = {v: int(k) for k, v in labels.items()}
fuente = Path(sys.argv[3] if len(sys.argv) > 3 else "data/manifest.jsonl")
por_clase = defaultdict(list)
creditos = {}
if fuente.is_dir():
    for carpeta in fuente.iterdir():
        if carpeta.is_dir() and carpeta.name in idx:
            por_clase[carpeta.name] = sorted(str(p) for p in carpeta.glob("*.jpg"))
    for l in open(fuente / "creditos.jsonl"):
        c = json.loads(l)
        creditos[str(fuente / c["clase"] / f"{c['obs']}.jpg")] = {
            "autor": c["atribucion"], "licencia": c["licencia"], "fuente": f"https://www.inaturalist.org/observations/{c['obs']}"}
else:
    for l in open(fuente):
        r = json.loads(l)
        if r["split"] in ("train", "val") and r["key"] != "no flower" and r["key"] in idx:
            por_clase[r["key"]].append(r["path"])
tf = T.Compose([T.Resize(256, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224), T.ToTensor(), T.Normalize(MEAN, STD)])
s = ort.InferenceSession(modelo, providers=["CPUExecutionProvider"])


def slug(k):
    return re.sub(r"[^a-z0-9]+", "-", k.lower()).strip("-")


def centro(im, f):
    lado = int(min(im.size) * f)
    return im.crop(((im.width - lado) // 2, (im.height - lado) // 2, (im.width + lado) // 2, (im.height + lado) // 2))


def _abre(p):
    try:
        Image.open(p).convert("RGB")
        return True
    except Exception:
        return False


elegidas = {}


def petalos(im):
    h, sat, v = (np.asarray(centro(im, 0.6).resize((96, 96)).convert("HSV"), dtype=np.float32) / 255).transpose(2, 0, 1)
    verde = (h > 60 / 360) & (h < 170 / 360)
    return float((((sat > 0.35) & (v > 0.3) & ~verde) | ((sat < 0.15) & (v > 0.85))).mean())


for k, paths in sorted(por_clase.items()):
    paths = paths[:80]
    paths = [p for p in paths if _abre(p)]
    if not paths:
        continue
    ims = [Image.open(p).convert("RGB") for p in paths]
    x = torch.stack([tf(im) for im in ims]).numpy()
    l = np.concatenate([s.run(None, {"pixel_values": x[i:i + 40]})[0] for i in range(0, len(x), 40)])
    p = np.exp(l - l.max(1, keepdims=True))
    p /= p.sum(1, keepdims=True)
    mejor = paths[int((p[:, idx[k]] * (0.1 + np.array([petalos(im) for im in ims]))).argmax())]
    im = centro(Image.open(mejor).convert("RGB"), 0.8).resize((240, 240), Image.LANCZOS)
    im.save(out / f"{slug(k)}.webp", quality=80)
    if mejor in creditos:
        elegidas[slug(k)] = creditos[mejor]
if creditos:
    (out / "creditos.json").write_text(json.dumps(elegidas, ensure_ascii=False, indent=1, sort_keys=True))
print(len(list(out.glob("*.webp"))))
