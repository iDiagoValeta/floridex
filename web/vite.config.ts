import { createHash } from "node:crypto";
import { createReadStream, existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { defineConfig, type Plugin } from "vite";

// In dev, Vite refuses dynamic imports of files in public/. ONNX Runtime
// imports its .mjs loader from /ort/, so serve that folder untouched.
const ortCrudo: Plugin = {
  name: "ort-crudo",
  configureServer(server) {
    server.middlewares.use("/ort", (req, res, next) => {
      const f = join("public/ort", (req.url ?? "").split("?")[0]);
      if (!existsSync(f)) return next();
      res.setHeader("Content-Type", f.endsWith(".mjs") ? "text/javascript" : "application/wasm");
      createReadStream(f).pipe(res);
    });
  },
};

// Offline: list every built file (the model weights aside, transformers.js
// caches them) for the service worker to precache on install. Safari does not
// route a dedicated worker's script through the service worker, so caching on
// fetch alone left the classifier worker out; and the first visit is not
// controlled by the service worker, so the model's config.json (the list of
// flowers) must be precached too. VERSION hashes the files' contents, so a
// fixed card, sprite or photo reaches existing users with the next build.
const precache: Plugin = {
  name: "precache",
  apply: "build",
  closeBundle() {
    const dist = "dist";
    const ficheros = readdirSync(dist, { recursive: true, withFileTypes: true })
      .filter((d) => d.isFile())
      .map((d) => relative(dist, join(d.parentPath, d.name)))
      // .wasm files are cached when fetched (and by transformers.js); .woff
      // duplicates .woff2, which every supported browser reads.
      .filter((f) => !f.endsWith(".onnx") && !/\.(wasm|woff)$/.test(f) && f !== "sw.js" && f !== "precache.json")
      .sort();
    const huella = createHash("sha256");
    for (const f of ficheros) huella.update(f).update(readFileSync(join(dist, f)));
    huella.update(HUELLA_MODELO);
    const version = huella.digest("hex").slice(0, 12);
    writeFileSync(join(dist, "precache.json"), JSON.stringify(ficheros));
    const sw = join(dist, "sw.js");
    writeFileSync(sw, readFileSync(sw, "utf8").replace(/const VERSION = "[^"]*";/, `const VERSION = "floridex-${version}";`));
  },
};

// Fingerprint of the model's weights. transformers.js caches the model by URL,
// so a retrained model at the same path would never be fetched again: the app
// clears that cache when this value changes (main.ts).
const HUELLA_MODELO = createHash("sha256")
  .update(readFileSync("../shared/modelo/onnx/model_quantized.onnx"))
  .digest("hex")
  .slice(0, 16);

export default defineConfig({
  base: "./",
  plugins: [ortCrudo, precache],
  define: { __HUELLA_MODELO__: JSON.stringify(HUELLA_MODELO) },
  // Missing files must 404: transformers.js probes optional model files and an
  // SPA fallback (index.html with status 200) would be parsed as JSON.
  appType: "mpa",
  server: { host: true, port: 5190 },
  preview: { host: true, port: 5190 },
  optimizeDeps: { exclude: ["@huggingface/transformers"] },
  worker: { format: "es" },
});
