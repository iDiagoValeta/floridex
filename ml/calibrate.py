"""Temperature-scale the classifier on the validation split and bake the
temperature into the classifier weights (W/T, b/T).

Training used label smoothing, which caps softmax confidence well below what
the model's accuracy warrants: a correct answer showed up as "51 %". Scaling
by one temperature leaves every argmax unchanged and makes the shown
percentage track how often the model is right. Also reports accuracy and
coverage above candidate "unsure" thresholds so the apps can pick one.
"""
import argparse
import json
from pathlib import Path

import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader
from torchvision import transforms as T
from transformers import AutoImageProcessor, Dinov2ForImageClassification

from train import MEAN, STD, Flowers


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--manifest", default="data/manifest.jsonl")
    ap.add_argument("--classes", default="data/classes.json")
    args = ap.parse_args()
    src, dst = Path(args.src), Path(args.dst)
    rows = [json.loads(l) for l in open(args.manifest)]
    classes = json.loads(Path(args.classes).read_text())
    cls_idx = {c: i for i, c in enumerate(classes)}
    tf = T.Compose([T.Resize(256, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224), T.ToTensor(), T.Normalize(MEAN, STD)])
    model = Dinov2ForImageClassification.from_pretrained(src).cuda().eval()


    @torch.no_grad()
    def logits_of(split):
        dl = DataLoader(Flowers([r for r in rows if r["split"] == split], cls_idx, tf), batch_size=128, num_workers=12)
        out, ys = [], []
        for x, y, _ in dl:
            out.append(model(pixel_values=x.cuda()).logits.float().cpu())
            ys.append(y)
        return torch.cat(out), torch.cat(ys)


    def ece(p, y, bins=15):
        conf, pred = p.max(1)
        e = torch.zeros(())
        for lo in torch.linspace(0, 1, bins + 1)[:-1]:
            m = (conf > lo) & (conf <= lo + 1 / bins)
            if m.any():
                e += m.float().mean() * ((pred[m] == y[m]).float().mean() - conf[m].mean()).abs()
        return e.item()


    val_logits, val_y = logits_of("val")
    log_t = torch.zeros((), requires_grad=True)
    opt = torch.optim.LBFGS([log_t], lr=0.1, max_iter=200)


    def closure():
        opt.zero_grad()
        loss = F.cross_entropy(val_logits / log_t.exp(), val_y)
        loss.backward()
        return loss


    opt.step(closure)
    t = log_t.exp().item()
    test_logits, test_y = logits_of("test")
    print(f"temperature {t:.3f}")
    for name, lg, y in (("val", val_logits, val_y), ("test", test_logits, test_y)):
        print(f"{name}: ECE before {ece(lg.softmax(1), y):.3f} after {ece((lg / t).softmax(1), y):.3f}")
    p = (test_logits / t).softmax(1)
    conf, pred = p.max(1)
    for th in (0.3, 0.4, 0.5, 0.6, 0.7):
        m = conf >= th
        print(f"threshold {th}: covers {m.float().mean():.3f} of test, accuracy there {(pred[m] == test_y[m]).float().mean():.3f}, below it {(pred[~m] == test_y[~m]).float().mean():.3f}")

    with torch.no_grad():
        model.classifier.weight /= t
        model.classifier.bias /= t
    model.save_pretrained(dst)
    AutoImageProcessor.from_pretrained(src).save_pretrained(dst)
    (dst / "temperature.json").write_text(json.dumps({"temperature": t}))


if __name__ == "__main__":
    main()
