"""Fixed sample of held-out test photos for the apps' end-to-end checks, with
what the shipped int8 ONNX model predicts for each one in Python, so the apps
are checked for parity with Python, not only for accuracy.

    uv run --with onnxruntime --with pillow --with numpy python shared/muestra.py \\
        <web sample dir> [per dataset for the web] [per dataset for Android]

Writes `<dir>/muestra.json` (absolute paths, Python's top 3 with their
probabilities) for `web/e2e/*.mjs`; the Android instrumented-test sample,
`android/app/src/androidTest/assets/muestra/` (photos downscaled to 384 px)
plus its `muestra.json`; and the share test's photos,
`android/emisora/src/main/assets/` (one clear photo per dataset) plus
`compartir.json` with the name the app must report. These photos come from
the training datasets and are not published: they are gitignored and this
script recreates them. Reads the test split of `ml/data/manifest2.jsonl` and
the model in `shared/modelo/`, so it always describes the model the apps ship. The seed is fixed: the same
model gives the same sample. `camara.y4m` (Chromium's fake webcam) is kept
if the directory already has one.
"""

import json
import random
import subprocess
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

RAIZ = Path(__file__).resolve().parent.parent
ML = RAIZ / "ml"
MODELO = RAIZ / "shared/modelo"
ANDROID = RAIZ / "android/app/src/androidTest/assets"
EMISORA = RAIZ / "android/emisora/src/main/assets"
MEDIA = np.array([0.485, 0.456, 0.406], dtype=np.float32)
DESV = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def tensor(path: Path) -> np.ndarray:
    """The model's preprocessing: shortest side to 256 (bicubic), centre crop 224, ImageNet mean and std."""
    im = Image.open(path).convert("RGB")
    # As torchvision's Resize(256): the long side is truncated, not rounded.
    if im.width <= im.height:
        w, h = 256, int(256 * im.height / im.width)
    else:
        w, h = int(256 * im.width / im.height), 256
    im = im.resize((w, h), Image.Resampling.BICUBIC)
    x0, y0 = int(round((w - 224) / 2)), int(round((h - 224) / 2))
    a = np.asarray(im.crop((x0, y0, x0 + 224, y0 + 224)), dtype=np.float32) / 255
    return ((a - MEDIA) / DESV).transpose(2, 0, 1)


def main():
    out = Path(sys.argv[1])
    por_web = int(sys.argv[2]) if len(sys.argv) > 2 else 12
    por_android = int(sys.argv[3]) if len(sys.argv) > 3 else 50
    out.mkdir(parents=True, exist_ok=True)

    config = json.loads((MODELO / "config.json").read_text())
    etiquetas = [config["id2label"][str(i)] for i in range(len(config["id2label"]))]
    sesion = ort.InferenceSession(str(MODELO / "onnx/model_quantized.onnx"), providers=["CPUExecutionProvider"])

    filas = [json.loads(l) for l in open(ML / "data/manifest2.jsonl")]
    # Only datasets with a test split (some only add training photos).
    datasets = sorted({r["dataset"] for r in filas if r["split"] == "test"})
    rng = random.Random(7)
    elegidas = {}
    for ds in datasets:
        test = [r for r in filas if r["dataset"] == ds and r["split"] == "test" and r["key"] in etiquetas]
        elegidas[ds] = rng.sample(test, min(len(test), max(por_web, por_android)))

    def predecir(rs):
        """Top 3 (label, probability) per photo."""
        x = np.stack([tensor(ML / r["path"]) for r in rs])
        z = sesion.run(None, {"pixel_values": x})[0]
        p = np.exp(z - z.max(1, keepdims=True))
        p /= p.sum(1, keepdims=True)
        return [[(etiquetas[i], round(float(f[i]), 4)) for i in f.argsort()[::-1][:3]] for f in p]

    web, android = [], []
    for ds, rs in elegidas.items():
        preds = predecir(rs)
        for i, (r, top) in enumerate(zip(rs, preds)):
            if i < por_web:
                # python_top3 lets the web check tell a near tie from a real disagreement.
                web.append(dict(path=str((ML / r["path"]).resolve()), dataset=ds, key=r["key"], python_pred=top[0][0], python_top3=top))
            if i < por_android:
                android.append((r, ds, top[0][0]))
        aciertos = sum(r["key"] == top[0][0] for r, top in zip(rs, preds))
        print(f"{ds}: python correct {aciertos}/{len(rs)}")

    (out / "muestra.json").write_text(json.dumps(web, indent=1, ensure_ascii=False) + "\n")

    destino = ANDROID / "muestra"
    if destino.exists():
        subprocess.run(["gio", "trash", str(destino)], check=True)
    destino.mkdir(parents=True)
    indice = []
    for n, (r, ds, _) in enumerate(android):
        archivo = f"{n:03d}.jpg"
        im = Image.open(ML / r["path"]).convert("RGB")
        im.thumbnail((384, 384), Image.Resampling.LANCZOS)
        im.save(destino / archivo, quality=90)
        indice.append(dict(archivo=archivo, dataset=ds, clave=r["key"]))
    # Python's answer on the very file the phone will read, downscaled as it is.
    for i in range(0, len(indice), 64):
        lote = indice[i:i + 64]
        x = np.stack([tensor(destino / e["archivo"]) for e in lote])
        z = sesion.run(None, {"pixel_values": x})[0]
        p = np.exp(z - z.max(1, keepdims=True))
        p /= p.sum(1, keepdims=True)
        for e, f in zip(lote, p):
            e["python"] = etiquetas[int(f.argmax())]
            # Python's confidence, so the phone's capture or ask decision can be checked too.
            e["p"] = round(float(f.max()), 4)
    (ANDROID / "muestra.json").write_text(json.dumps(indice, indent=0, ensure_ascii=False) + "\n")
    # Share test: per dataset, the first photo Python names right with confidence.
    nombres = {f["key"]: f["nombre"] for f in json.loads((RAIZ / "shared/floridex.json").read_text())}
    if EMISORA.exists():
        subprocess.run(["gio", "trash", str(EMISORA)], check=True)
    EMISORA.mkdir(parents=True)
    compartir = {}
    for e in indice:
        if e["dataset"] in {Path(k).stem for k in compartir} or e["python"] != e["clave"] or e["p"] < 0.9:
            continue
        archivo = f"{e['dataset']}.jpg"
        (EMISORA / archivo).write_bytes((destino / e["archivo"]).read_bytes())
        compartir[archivo] = "No veo ninguna flor" if e["clave"] == "no flower" else nombres[e["clave"]]
    (EMISORA / "compartir.json").write_text(json.dumps(compartir, indent=1, ensure_ascii=False) + "\n")
    print(f"web: {len(web)} photos in {out}; android: {len(indice)} photos in {destino}; share test: {len(compartir)} photos")


if __name__ == "__main__":
    main()
