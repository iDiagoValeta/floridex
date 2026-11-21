// Fills public/ with what the app serves but does not own: onnxruntime-web's
// wasm binaries (from our own origin, not a CDN, so the app works offline
// and no photo-related request leaves the device) and the app data shared
// with Android, kept once in ../shared. Everything written here is generated
// and gitignored. Runs before `vite` and `vite build` (package.json).
import { cpSync, mkdirSync, readdirSync, realpathSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";

function limpio(dir) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
}

// pnpm nests onnxruntime-web next to transformers.js inside its store folder.
const tjs = realpathSync("node_modules/@huggingface/transformers");
const ort = join(dirname(tjs), "..", "onnxruntime-web", "dist");
// The wasm device uses the asyncify build, or the plain one on Safari < 26
// without WebGPU (the same choice transformers.js makes).
limpio("public/ort");
for (const f of readdirSync(ort)) {
  if (/^ort-wasm-simd-threaded(\.asyncify)?\.(mjs|wasm)$/.test(f)) cpSync(join(ort, f), join("public/ort", f));
}

// App data: cards, sprites, reference photos (with their credits) and the model.
const shared = "../shared";
cpSync(join(shared, "floridex.json"), "public/floridex.json");
limpio("public/sprites");
cpSync(join(shared, "sprites"), "public/sprites", { recursive: true });
limpio("public/miniaturas");
for (const f of readdirSync(join(shared, "miniaturas2"))) {
  if (f.endsWith(".webp")) cpSync(join(shared, "miniaturas2", f), join("public/miniaturas", f));
}
limpio("public/models/floridex/onnx");
for (const f of ["config.json", "preprocessor_config.json"]) cpSync(join(shared, "modelo", f), join("public/models/floridex", f));
cpSync(join(shared, "modelo/onnx/model_quantized.onnx"), "public/models/floridex/onnx/model_quantized.onnx");
