// Scores ONNX models with onnxruntime-web's WebAssembly kernels, the ones the
// browser runs, over test tensors written by ml/eval_tensors.py.
//
//   node e2e/evaluar-ort-web.mjs <tensor dir> <model.onnx> [<model.onnx> ...]
//
// Reports accuracy per dataset (restricted to that dataset's classes, the
// non-flower set over every class) and top-1 agreement with the first model.
import { openSync, readFileSync, readSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";
import { realpathSync } from "node:fs";
import { dirname } from "node:path";

const tjs = realpathSync(new URL("../node_modules/@huggingface/transformers", import.meta.url).pathname);
const ort = createRequire(join(dirname(tjs), "..", "onnxruntime-web", "package.json"))("onnxruntime-web");
ort.env.wasm.numThreads = 4;

const [dir, ...modelos] = process.argv.slice(2);
const items = JSON.parse(readFileSync(join(dir, "items.json"), "utf8"));
const meta = JSON.parse(readFileSync(join(dir, "meta.json"), "utf8"));
// Read one tensor at a time: the full test set is several GB.
const fd = openSync(join(dir, "x.bin"), "r");
const tam = 3 * 224 * 224;
const idx = Object.fromEntries(meta.labels.map((l, i) => [l, i]));
const masks = Object.fromEntries(Object.entries(meta.masks).map(([k, v]) => [k, new Set(v)]));

let referencia = null;
for (const m of modelos) {
  const s = await ort.InferenceSession.create(m, { executionProviders: ["wasm"] });
  const aciertos = {}, total = {}, preds = [];
  for (let i = 0; i < items.length; i++) {
    const data = new Float32Array(tam);
    readSync(fd, new Uint8Array(data.buffer), 0, tam * 4, i * tam * 4);
    const out = await s.run({ pixel_values: new ort.Tensor("float32", data, [1, 3, 224, 224]) });
    const l = out.logits.data;
    const ds = items[i].dataset;
    let best = -1, bv = -Infinity, top = -1, tv = -Infinity;
    for (let k = 0; k < l.length; k++) {
      if (l[k] > tv) { tv = l[k]; top = k; }
      if (masks[ds].has(k) && l[k] > bv) { bv = l[k]; best = k; }
    }
    preds.push(top);
    aciertos[ds] = (aciertos[ds] ?? 0) + (best === idx[items[i].key]);
    total[ds] = (total[ds] ?? 0) + 1;
  }
  const res = Object.fromEntries(Object.keys(total).map((k) => [k, +(aciertos[k] / total[k]).toFixed(4)]));
  if (referencia) res.acuerdo_con_primero = +(preds.filter((p, i) => p === referencia[i]).length / preds.length).toFixed(4);
  else referencia = preds;
  console.log(m.split("/").slice(-1)[0], JSON.stringify(res));
}
