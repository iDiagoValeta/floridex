"""Score a trained model on every v2 test benchmark, and on the part of each
benchmark that an older model can also answer, so old and new are compared on
exactly the same photos.

    python eval_comparado.py <model dir> <out.json> [--subset-of <old classes.json>]

Per benchmark: top-1 and top-5 with the prediction restricted to the classes of
that benchmark (the published protocol), plus top-1 over all classes ("open").
With --subset-of, only test photos whose class the old model knows are scored,
and predictions are restricted to classes both know.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import torch
from torch.utils.data import DataLoader
from torchvision import transforms as T
from transformers import Dinov2ForImageClassification

from train import MEAN, STD, Flowers


@torch.no_grad()
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("modelo")
    ap.add_argument("salida")
    ap.add_argument("--manifest", default="data/manifest2.jsonl")
    ap.add_argument("--subset-of", default=None)
    args = ap.parse_args()

    model = Dinov2ForImageClassification.from_pretrained(args.modelo, attn_implementation="sdpa").cuda().eval()
    clases = [model.config.id2label[i] for i in range(len(model.config.id2label))]
    idx = {c: i for i, c in enumerate(clases)}
    rows = [json.loads(l) for l in open(args.manifest)]
    conocidas = set(json.load(open(args.subset_of))) if args.subset_of else set(clases)
    test = [r for r in rows if r["split"] == "test" and r["key"] in conocidas and r["key"] in idx]
    mascaras = {}
    for ds in {r["dataset"] for r in rows}:
        m = torch.zeros(len(clases), dtype=torch.bool)
        for r in rows:
            if r["dataset"] == ds and r["key"] in idx and r["key"] in conocidas:
                m[idx[r["key"]]] = True
        if ds == "no_flor":
            m[:] = torch.tensor([c in conocidas for c in clases])
        mascaras[ds] = m.cuda()
    abierta = torch.tensor([c in conocidas for c in clases], device="cuda")
    tf = T.Compose([T.Resize(256, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224), T.ToTensor(), T.Normalize(MEAN, STD)])
    dl = DataLoader(Flowers(test, idx, tf), batch_size=128, num_workers=14)
    h = defaultdict(lambda: [0, 0, 0, 0])
    for x, y, i in dl:
        with torch.autocast("cuda", dtype=torch.bfloat16):
            lg = model(pixel_values=x.cuda()).logits.float()
        y = y.cuda()
        for j, k in enumerate(i.tolist()):
            ds = test[k]["dataset"]
            r = lg[j].masked_fill(~mascaras[ds], -1e9)
            e = h[ds]
            e[0] += int(r.argmax() == y[j])
            e[1] += int(y[j] in r.topk(5).indices)
            e[2] += int(lg[j].masked_fill(~abierta, -1e9).argmax() == y[j])
            e[3] += 1
    res = {ds: dict(top1=e[0] / e[3], top5=e[1] / e[3], abierto=e[2] / e[3], n=e[3]) for ds, e in sorted(h.items())}
    print(json.dumps(res, indent=1))
    Path(args.salida).write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
