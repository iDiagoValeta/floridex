"""Score ONNX models on the held-out iNaturalist garden sets (data/raw/inat_eval/<class>/),
observations never used for training, to measure look-alike confusions such as
gerbera versus calendula.

    python eval_jardin.py <model dir> [<model dir> ...]
"""
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
from torchvision import transforms as T

from train import MEAN, STD

tf = T.Compose([T.Resize(256, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224), T.ToTensor(), T.Normalize(MEAN, STD)])
for d in sys.argv[1:]:
    total, aciertos, por_clase = 0, 0, []
    lab = json.load(open(f"{d}/config.json"))["id2label"]
    s = ort.InferenceSession(f"{d}/onnx/model_quantized.onnx", providers=["CPUExecutionProvider"])
    for carpeta in sorted(Path("data/raw/inat_eval").iterdir()):
        if not carpeta.is_dir():
            continue
        fotos = []
        for f in sorted(carpeta.glob("*.jpg")):
            try:
                fotos.append(tf(Image.open(f).convert("RGB")).numpy())
            except Exception:
                pass
        if not fotos:
            continue
        pred = Counter()
        for i in range(0, len(fotos), 32):
            pred.update(lab[str(k)] for k in s.run(None, {"pixel_values": np.stack(fotos[i:i + 32])})[0].argmax(1))
        total += len(fotos)
        aciertos += pred[carpeta.name]
        por_clase.append(pred[carpeta.name] / len(fotos))
        print(f"{Path(d).name:12} {carpeta.name:18} {pred[carpeta.name]}/{len(fotos)} = {pred[carpeta.name] / len(fotos):.3f}  {pred.most_common(4)}", flush=True)
    print(f"{Path(d).name}: {aciertos}/{total} photos = {aciertos / total:.3f}; mean per class {sum(por_clase) / len(por_clase):.3f} ({len(por_clase)} classes)", flush=True)
