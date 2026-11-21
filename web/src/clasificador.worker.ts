// Runs the flower classifier off the main thread so the viewfinder stays fluid.
import { AutoModelForImageClassification, env, Tensor, type PreTrainedModel } from "@huggingface/transformers";
import { preparar, type Preprocesado } from "./preparar";

env.allowRemoteModels = false;
env.allowLocalModels = true;

/**
 * Points transformers.js at the app's own files. The page sends its root URL:
 * relative to this worker's own URL a "./" base would land in /assets/.
 */
let raizApp = "";
function configurar(raiz: string, asyncify: boolean) {
  raizApp = raiz;
  // A path, not a full URL: transformers.js 4.3 skips its local-file lookup
  // for http(s) URLs, which silently drops preprocessor_config.json.
  env.localModelPath = new URL("models/", raiz).pathname;
  // ONNX Runtime from our own origin; left unset it would come from a CDN.
  const ort = `ort-wasm-simd-threaded${asyncify ? ".asyncify" : ""}`;
  if (env.backends.onnx.wasm) {
    env.backends.onnx.wasm.wasmPaths = { mjs: new URL(`ort/${ort}.mjs`, raiz).href, wasm: new URL(`ort/${ort}.wasm`, raiz).href };
  }
}

export type Mensaje =
  | { tipo: "listo" }
  | { tipo: "progreso"; fraccion: number }
  | { tipo: "resultado"; id: number; candidatos: { label: string; score: number }[]; ms: number }
  | { tipo: "error"; id?: number; mensaje: string };

type Modelo = { modelo: PreTrainedModel; prep: Preprocesado; etiquetas: Record<string, string> };
let clasificador: Promise<Modelo> | null = null;
let fallido = false;

/**
 * The model and its preprocessing settings (preprocessor_config.json), read
 * from the app's own files: a new model with other settings needs no code change.
 */
async function abrirModelo(): Promise<Modelo> {
  const [modelo, cfg] = await Promise.all([
    AutoModelForImageClassification.from_pretrained("floridex", {
      dtype: "q8",
      device: "wasm",
      progress_callback: (p: { status: string; progress?: number }) => {
        if (p.status === "progress" && typeof p.progress === "number") {
          self.postMessage({ tipo: "progreso", fraccion: p.progress / 100 } satisfies Mensaje);
        }
      },
    }),
    fetch(new URL("models/floridex/preprocessor_config.json", raizApp)).then((r) => r.json()),
  ]);
  return {
    modelo,
    prep: { lado: cfg.size.shortest_edge, recorte: cfg.crop_size.height, media: cfg.image_mean, desv: cfg.image_std },
    etiquetas: (modelo.config as unknown as { id2label: Record<string, string> }).id2label,
  };
}

function cargar() {
  clasificador ??= abrirModelo();
  clasificador.catch(() => (fallido = true));
  return clasificador;
}

/** Pixels of a photo, upright (createImageBitmap applies the EXIF orientation). */
async function pixeles(imagen: Blob | ImageBitmap) {
  const bmp = imagen instanceof Blob ? await createImageBitmap(imagen) : imagen;
  const g = new OffscreenCanvas(bmp.width, bmp.height).getContext("2d")!;
  g.drawImage(bmp, 0, 0);
  const d = g.getImageData(0, 0, bmp.width, bmp.height);
  bmp.close();
  return d;
}

/** One retry: a flaky first download (or a reload in the middle of it) should not leave the app without a model. */
async function cargarConReintento() {
  try {
    return await cargar();
  } catch {
    await new Promise((r) => setTimeout(r, 1500));
    // Whoever gets here first starts the second attempt; the rest share it.
    if (fallido) clasificador = null;
    fallido = false;
    return await cargar();
  }
}

self.onmessage = async (e: MessageEvent<{ tipo: "cargar"; raiz: string; asyncify: boolean } | { tipo: "clasificar"; id: number; imagen: Blob | ImageBitmap }>) => {
  const m = e.data;
  try {
    if (m.tipo === "cargar") {
      configurar(m.raiz, m.asyncify);
      await cargarConReintento();
      self.postMessage({ tipo: "listo" } satisfies Mensaje);
      return;
    }
    // A photo can arrive while the first download is failing: it waits for the retry.
    const { modelo, prep, etiquetas } = await cargarConReintento();
    const t0 = performance.now();
    const d = await pixeles(m.imagen);
    const x = new Tensor("float32", preparar(d.data, d.width, d.height, prep), [1, 3, prep.recorte, prep.recorte]);
    const { logits } = (await modelo({ pixel_values: x })) as { logits: Tensor };
    // Softmax of the calibrated logits: the shown percentage.
    const z = logits.data as Float32Array;
    const max = Math.max(...z);
    const e = Array.from(z, (v) => Math.exp(v - max));
    const total = e.reduce((a, b) => a + b, 0);
    const candidatos = e
      .map((v, i) => ({ label: etiquetas[i], score: v / total }))
      .sort((a, b) => b.score - a.score)
      .slice(0, 5);
    self.postMessage({ tipo: "resultado", id: m.id, candidatos, ms: performance.now() - t0 } satisfies Mensaje);
  } catch (err) {
    self.postMessage({ tipo: "error", id: m.tipo === "clasificar" ? m.id : undefined, mensaje: String(err) } satisfies Mensaje);
  }
};
