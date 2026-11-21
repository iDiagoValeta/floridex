"""Export the trained classifier to ONNX in the layout transformers.js expects,
quantize it to 8 bits, and check that the ONNX models score like PyTorch.

Output (models/floridex):
  config.json, preprocessor_config.json
  onnx/model.onnx            fp32
  onnx/model_quantized.onnx  int8 weights, what the web and Android apps load
"""
import argparse
import json
import shutil
from collections import defaultdict
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from PIL import Image
from torchvision import transforms as T
from transformers import Dinov2ForImageClassification

from train import DATASETS, MEAN, STD


class Logits(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, pixel_values):
        return self.m(pixel_values=pixel_values).logits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="runs/dinov2-small/best")
    ap.add_argument("--out", default="models/floridex")
    ap.add_argument("--manifest", default="data/manifest.jsonl")
    ap.add_argument("--classes", default="data/classes.json")
    args = ap.parse_args()

    out = Path(args.out)
    (out / "onnx").mkdir(parents=True, exist_ok=True)
    model = Dinov2ForImageClassification.from_pretrained(args.run).eval()
    for f in ("config.json", "preprocessor_config.json"):
        shutil.copy(Path(args.run) / f, out / f)

    fp32 = out / "onnx/model.onnx"
    torch.onnx.export(
        Logits(model), (torch.randn(1, 3, 224, 224),), str(fp32),
        input_names=["pixel_values"], output_names=["logits"],
        dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=17, dynamo=False,
    )
    q8 = out / "onnx/model_quantized.onnx"
    # Per-channel scales: per-tensor int8 drifted from fp32 on borderline photos,
    # and differently on the browser's WebAssembly kernels than on CPU.
    quantize_dynamic(str(fp32), str(q8), weight_type=QuantType.QUInt8, per_channel=True)
    for p in (fp32, q8):
        print(p, f"{p.stat().st_size / 1e6:.1f} MB")

    # Parity on every test image: restricted accuracy per dataset for each backend.
    classes = json.loads(Path(args.classes).read_text())
    cls_idx = {c: i for i, c in enumerate(classes)}
    rows = [json.loads(l) for l in open(args.manifest)]
    masks = defaultdict(lambda: np.zeros(len(classes), bool))
    for r in rows:
        masks[r["dataset"]][cls_idx[r["key"]]] = True
    # Non-flower photos are scored over every class: any flower answer is wrong.
    masks["no_flor"][:] = True
    test = [r for r in rows if r["split"] == "test"]
    tf = T.Compose([T.Resize(256, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224), T.ToTensor(), T.Normalize(MEAN, STD)])
    sessions = {
        name: ort.InferenceSession(str(p), providers=["CPUExecutionProvider"])
        for name, p in (("onnx_fp32", fp32), ("onnx_q8", q8))
    }
    hits = {name: defaultdict(lambda: [0, 0]) for name in sessions}
    agree = 0
    rejected_flowers = 0  # flower photos the q8 model called "no flower"
    no_flor = cls_idx.get("no flower")
    bs = 64
    for i in range(0, len(test), bs):
        batch = test[i:i + bs]
        x = torch.stack([tf(Image.open(r["path"]).convert("RGB")) for r in batch]).numpy()
        preds = {}
        for name, s in sessions.items():
            logits = s.run(None, {"pixel_values": x})[0]
            preds[name] = logits.argmax(1)
            for j, r in enumerate(batch):
                restricted = np.where(masks[r["dataset"]], logits[j], -1e9).argmax()
                h = hits[name][r["dataset"]]
                h[0] += int(restricted == cls_idx[r["key"]])
                h[1] += 1
        agree += int((preds["onnx_fp32"] == preds["onnx_q8"]).sum())
        if no_flor is not None:
            rejected_flowers += sum(int(p == no_flor) for p, r in zip(preds["onnx_q8"], batch) if r["dataset"] != "no_flor")
    report = {name: {ds: h[0] / h[1] for ds, h in d.items()} for name, d in hits.items()}
    report["flowers_rejected_as_no_flower_q8"] = rejected_flowers / sum(r["dataset"] != "no_flor" for r in test)
    report["top1_agreement_fp32_vs_q8"] = agree / len(test)
    print(json.dumps(report, indent=1))
    (out / "parity.json").write_text(json.dumps(report, indent=1))
    for ds in DATASETS:
        assert report["onnx_q8"][ds] >= 0.95, f"{ds} below 95% after quantization"
    if "no_flor" in report["onnx_q8"]:
        assert report["onnx_q8"]["no_flor"] >= 0.95, "too many non-flower photos named as a flower"


if __name__ == "__main__":
    main()
