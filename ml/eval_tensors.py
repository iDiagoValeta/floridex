"""Write preprocessed test tensors so ONNX Runtime Web can be scored outside
the browser (web/e2e/evaluar-ort-web.mjs). The browser's int8 kernels differ
from the CPU ones Python uses, so the web needs its own accuracy number.

Writes <out>/x.bin (float32, N x 3 x 224 x 224) and <out>/items.json.
"""
import json
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from torchvision import transforms as T

from train import DATASETS, MEAN, STD

out = Path(sys.argv[1])
per = int(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
rows = [json.loads(l) for l in open("data/manifest.jsonl")]
rng = random.Random(11)
items = []
for ds in DATASETS + ["no_flor"]:
    test = [r for r in rows if r["dataset"] == ds and r["split"] == "test"]
    items += rng.sample(test, min(per, len(test)))
tf = T.Compose([T.Resize(256, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224), T.ToTensor(), T.Normalize(MEAN, STD)])
with open(out / "x.bin", "wb") as f:
    for r in items:
        f.write(tf(Image.open(r["path"]).convert("RGB")).numpy().astype(np.float32).tobytes())
(out / "items.json").write_text(json.dumps([dict(dataset=r["dataset"], key=r["key"]) for r in items]))
print(len(items))
