"""Fine-tune a DINOv2 classifier on the unified flower manifest.

Evaluation follows each dataset's own protocol: the prediction is restricted to
the classes that dataset contains. The open-set number (argmax over all 182
classes) is reported too.
"""
import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

import torch
import torch.nn.functional as F
from PIL import Image
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from torchvision import transforms as T
from transformers import AutoImageProcessor, Dinov2ForImageClassification

MEAN, STD = (0.485, 0.456, 0.406), (0.229, 0.224, 0.225)
DATASETS = ["oxford102", "oxford17", "tf_flowers", "swedish"]
NO_FLOR = "no flower"
# Share of each batch the reject class gets, relative to its sqrt-count weight.
NEG_BOOST = 2.5


class Flowers(Dataset):
    def __init__(self, rows, cls_idx, tf):
        self.rows, self.cls_idx, self.tf = rows, cls_idx, tf

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        r = self.rows[i]
        return self.tf(Image.open(r["path"]).convert("RGB")), self.cls_idx[r["key"]], i


def masks_for(rows, cls_idx, n):
    # The reject set is scored over every class: any flower answer is wrong.
    m = {"no_flor": torch.ones(n, dtype=torch.bool)}
    for ds in sorted({r["dataset"] for r in rows} - {"no_flor"}):
        keep = torch.zeros(n, dtype=torch.bool)
        for r in rows:
            if r["dataset"] == ds:
                keep[cls_idx[r["key"]]] = True
        m[ds] = keep
    return m


@torch.no_grad()
def evaluate(model, loader, rows, masks, device):
    model.eval()
    hits = defaultdict(lambda: [0, 0, 0, 0])  # restricted top-1, open top-1, total, restricted top-5
    for x, y, idx in loader:
        with torch.autocast("cuda", dtype=torch.bfloat16):
            logits = model(pixel_values=x.to(device)).logits.float()
        y = y.to(device)
        open_pred = logits.argmax(1)
        for j, i in enumerate(idx.tolist()):
            ds = rows[i]["dataset"]
            restringidos = logits[j].masked_fill(~masks[ds].to(device), -1e9)
            restricted = restringidos.argmax()
            h = hits[ds]
            h[0] += int(restricted == y[j])
            h[3] += int(y[j] in restringidos.topk(5).indices)
            h[1] += int(open_pred[j] == y[j])
            h[2] += 1
    return {ds: dict(acc=h[0] / h[2], open_acc=h[1] / h[2], top5=h[3] / h[2], n=h[2]) for ds, h in hits.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="facebook/dinov2-small")
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--bs", type=int, default=64)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--head-lr", type=float, default=1e-3)
    ap.add_argument("--out", default="runs/dinov2-small")
    ap.add_argument("--manifest", default="data/manifest.jsonl")
    ap.add_argument("--classes", default="data/classes.json")
    args = ap.parse_args()

    torch.manual_seed(23)
    device = "cuda"
    rows = [json.loads(l) for l in open(args.manifest)]
    classes = json.loads(Path(args.classes).read_text())
    cls_idx = {c: i for i, c in enumerate(classes)}
    split = {s: [r for r in rows if r["split"] == s] for s in ("train", "val", "test")}
    masks = masks_for(rows, cls_idx, len(classes))

    train_tf = T.Compose([
        T.RandomResizedCrop(224, scale=(0.3, 1.0), interpolation=T.InterpolationMode.BICUBIC),
        T.RandomHorizontalFlip(),
        T.RandomApply([T.RandomRotation(20, interpolation=T.InterpolationMode.BILINEAR)], p=0.3),
        T.ColorJitter(0.3, 0.3, 0.2, 0.02),
        T.ToTensor(), T.Normalize(MEAN, STD),
    ])
    # Same as the DINOv2 image processor that transformers.js will apply.
    eval_tf = T.Compose([
        T.Resize(256, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224),
        T.ToTensor(), T.Normalize(MEAN, STD),
    ])

    counts = Counter(r["key"] for r in split["train"])
    weights = [(NEG_BOOST if r["key"] == NO_FLOR else 1) / math.sqrt(counts[r["key"]]) for r in split["train"]]
    sampler = WeightedRandomSampler(weights, num_samples=len(weights), replacement=True)
    kw = dict(num_workers=14, pin_memory=True, persistent_workers=True)
    train_dl = DataLoader(Flowers(split["train"], cls_idx, train_tf), batch_size=args.bs, sampler=sampler, drop_last=True, **kw)
    val_dl = DataLoader(Flowers(split["val"], cls_idx, eval_tf), batch_size=128, **kw)
    test_dl = DataLoader(Flowers(split["test"], cls_idx, eval_tf), batch_size=128, **kw)

    model = Dinov2ForImageClassification.from_pretrained(
        args.model, num_labels=len(classes),
        id2label=dict(enumerate(classes)), label2id=cls_idx,
        attn_implementation="sdpa",  # 1.5x faster than eager and lighter on the 8 GB GPU
    ).to(device)
    head = [p for n, p in model.named_parameters() if n.startswith("classifier")]
    body = [p for n, p in model.named_parameters() if not n.startswith("classifier")]
    opt = torch.optim.AdamW([
        dict(params=body, lr=args.lr), dict(params=head, lr=args.head_lr),
    ], weight_decay=0.05)
    total = args.epochs * len(train_dl)
    warm = len(train_dl)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: s / warm if s < warm else 0.5 * (1 + math.cos(math.pi * (s - warm) / max(1, total - warm))))

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    best = -1.0
    for epoch in range(args.epochs):
        model.train()
        running = 0.0
        for x, y, _ in train_dl:
            with torch.autocast("cuda", dtype=torch.bfloat16):
                logits = model(pixel_values=x.to(device, non_blocking=True)).logits
            loss = F.cross_entropy(logits.float(), y.to(device), label_smoothing=0.1)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            running += loss.item()
        val = evaluate(model, val_dl, split["val"], masks, device)
        mean_val = sum(v["acc"] for v in val.values()) / len(val)
        print(f"epoch {epoch + 1} loss {running / len(train_dl):.4f} val "
              + " ".join(f"{k}={v['acc']:.4f}" for k, v in sorted(val.items()))
              + f" mean={mean_val:.4f}", flush=True)
        if mean_val > best:
            best = mean_val
            model.save_pretrained(out / "best")

    model = Dinov2ForImageClassification.from_pretrained(out / "best", attn_implementation="sdpa").to(device)
    AutoImageProcessor.from_pretrained(args.model).save_pretrained(out / "best")
    test = evaluate(model, test_dl, split["test"], masks, device)
    print("TEST", json.dumps(test, indent=1))
    (out / "test_metrics.json").write_text(json.dumps(test, indent=1))


if __name__ == "__main__":
    main()
