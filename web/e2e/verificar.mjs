// End-to-end check of the web app in real browser engines.
//
//   node e2e/verificar.mjs <url> <dir with muestra.json and camara.y4m> <out dir>
//
// For every sample photo it uploads the file through the UI, waits for the
// answer (the flower's card, the list of candidates when unsure, or "no
// flower") and compares the model's top name with the ground truth and with
// the name the same model gave in Python. Chromium also runs the live-camera path with
// a fake webcam that films a sunflower. WebKit stands in for iPhone Safari.
import { chromium, devices, webkit } from "playwright";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

const [url, dir, out] = process.argv.slice(2);
const muestra = JSON.parse(readFileSync(join(dir, "muestra.json"), "utf8"));
// Same as the app: under this confidence it offers a choice instead of capturing.
const UMBRAL_DUDA = 0.7;
const flores = Object.fromEntries(
  JSON.parse(readFileSync(new URL("../../shared/floridex.json", import.meta.url), "utf8")).map((f) => [f.key, f.nombre]),
);
// The reject class has no card: the app says it saw no flower.
flores["no flower"] = "No veo ninguna flor";

/**
 * The model's top answer and the app's decision as the UI tells them, then
 * dismisses the text box. Decisions: "captura" (identified outright),
 * "pregunta" (unsure: pick a candidate), "sinflor" (no flower seen).
 */
async function esperarDecision(page, espera = 120_000) {
  await page.waitForSelector('#dialogo:not([hidden])[data-escribiendo="false"]', { timeout: espera });
  const r = await page.evaluate(() => {
    const t = document.getElementById("dialogo-texto").textContent;
    const m = t.match(/^¡Capturada! (.+) se ha registrado/) ?? t.match(/^(.+) ya estaba en tu Floridex\.$/);
    if (m) return { visto: m[1], decision: "captura" };
    if (t.startsWith("No veo ninguna flor")) return { visto: "No veo ninguna flor", decision: "sinflor" };
    // Unsure: the candidates, in the model's order.
    return { visto: document.querySelector(".opcion-nombre")?.textContent ?? "", decision: "pregunta" };
  });
  await page.keyboard.press("Escape");
  return r;
}
const esperarRespuesta = async (page, espera) => (await esperarDecision(page, espera)).visto;

/** What the app should decide from Python's answer: the same rule as main.ts. */
function decisionPython(m) {
  const [k, p] = m.python_top3?.[0] ?? [m.python_pred, 1];
  if (k === "no flower") return "sinflor";
  return p >= UMBRAL_DUDA ? "captura" : "pregunta";
}

async function porSubida(nombre, browserType, contexto) {
  const browser = await browserType.launch();
  const page = await (await browser.newContext(contexto)).newPage();
  const errores = [];
  page.on("pageerror", (e) => errores.push(String(e)));
  // transformers.js probes the optional tokenizer_config.json, which an image
  // model does not have; WebKit logs that 404 as a console error.
  page.on("console", (m) => m.type() === "error" && !m.text().includes("status of 404") && errores.push(m.text()));
  await page.goto(url);
  let aciertos = 0, iguales = 0, graves = 0, decisiones = 0;
  const t0 = Date.now();
  for (const [i, m] of muestra.entries()) {
    await page.setInputFiles("#archivo", m.path);
    const { visto, decision } = await esperarDecision(page);
    aciertos += visto === flores[m.key];
    iguales += visto === flores[m.python_pred];
    const [, p1] = m.python_top3?.[0] ?? [null, 1];
    const esperada = decisionPython(m);
    // The browser decodes and resizes the photo itself, so a probability near
    // the 0.7 line can cross it: only then may the decision differ.
    const cerca = esperada !== "sinflor" && Math.abs(p1 - UMBRAL_DUDA) <= 0.05;
    if (decision !== esperada && !cerca) {
      decisiones++;
      console.log(`  [${nombre}] ${m.dataset}: web decides "${decision}", Python "${esperada}" (${p1}): DIFFERENT DECISION`);
    }
    if (visto !== flores[m.python_pred]) {
      // A different top answer is harmless only if both were unsure (the app
      // then offers the choice) and the web's answer is in Python's top 3.
      const empate = decision === "pregunta" && p1 < UMBRAL_DUDA && (m.python_top3 ?? []).some(([k]) => flores[k] === visto);
      if (!empate) graves++;
      console.log(`  [${nombre}] ${m.dataset}: web «${visto}», python «${flores[m.python_pred]}» (${p1}), true «${flores[m.key]}»${empate ? ", near tie" : ", DISAGREEMENT"}`);
    }
    if (i === 0) await page.screenshot({ path: join(out, `${nombre}-resultado.png`), fullPage: true });
  }
  const s = (Date.now() - t0) / muestra.length / 1000;
  console.log(`${nombre}: correct ${aciertos}/${muestra.length}, same as Python ${iguales}/${muestra.length} (disagreements that are not near ties: ${graves}, different decisions: ${decisiones}), ${s.toFixed(1)} s per photo (animation included)`);
  if (errores.length) console.log(`${nombre}: console errors`, errores);
  await browser.close();
  return { aciertos, iguales, graves, decisiones, errores: errores.length };
}

async function porCamara() {
  const browser = await chromium.launch({
    args: [
      "--use-fake-ui-for-media-stream",
      "--use-fake-device-for-media-stream",
      `--use-file-for-fake-video-capture=${join(dir, "camara.y4m")}`,
    ],
  });
  const ctx = await browser.newContext({ permissions: ["camera"], viewport: { width: 1280, height: 900 } });
  // Every camera stream the app opens, with the grant delayed as if a
  // permission prompt were still open.
  await ctx.addInitScript(() => {
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    window.__flujos = [];
    navigator.mediaDevices.getUserMedia = async (c) => {
      const s = await original(c);
      window.__flujos.push(s);
      await new Promise((r) => setTimeout(r, 500));
      return s;
    };
  });
  const page = await ctx.newPage();
  const encendidas = () => page.evaluate(() => window.__flujos.flatMap((s) => s.getVideoTracks()).filter((t) => t.readyState === "live").length);
  await page.goto(url);
  await page.click("#ir-camara");
  // Two quick presses while the first request is pending: one camera, not two.
  await page.click("#abrir-camara");
  await page.click("#abrir-camara");
  await page.waitForSelector('#marco[data-modo="vivo"]');
  await page.waitForTimeout(800);
  const doble = await encendidas();
  // Leaving the screen while a request is pending: the camera stays off.
  await page.click("#c-volver");
  await page.click("#ir-camara");
  await page.click("#abrir-camara");
  await page.click("#c-volver");
  await page.waitForTimeout(1500);
  const alSalir = await encendidas();
  console.log(`camera: live streams after two presses ${doble}, after leaving with a request pending ${alSalir}`);
  await page.click("#ir-camara");
  await page.click("#abrir-camara");
  await page.waitForSelector('#marco[data-modo="vivo"]');
  await page.waitForTimeout(800);
  await page.screenshot({ path: join(out, "camara-visor.png") });
  await page.click("#disparar");
  const visto = await esperarRespuesta(page);
  await page.screenshot({ path: join(out, "camara-resultado.png") });
  const guardadas = Number(await page.textContent("#capturadas"));
  console.log(`camera: «${visto}», flowers captured ${guardadas}`);
  await browser.close();
  return visto === "Girasol" && guardadas === 1 && doble === 1 && alSalir === 0;
}

// Production build only, the way a person meets it: a first visit (not yet
// controlled by the service worker), one photo identified, then the network
// goes and the page is reloaded. The index must still list the flowers and a
// new photo must still get an answer.
async function sinConexion(browserType, contexto = {}) {
  const browser = await browserType.launch();
  const ctx = await browser.newContext(contexto);
  const page = await ctx.newPage();
  await page.goto(url);
  await page.setInputFiles("#archivo", muestra[0].path);
  await esperarRespuesta(page);
  // The service worker installs in the background during that first visit.
  await page.evaluate(() => navigator.serviceWorker.ready);
  await ctx.setOffline(true);
  await page.reload();
  const entradas = await page
    .waitForFunction(() => document.querySelectorAll("#lista li").length, null, { timeout: 15_000 })
    .then((h) => h.jsonValue())
    .catch(() => 0);
  let visto = "(sin respuesta)";
  if (entradas) {
    await page.setInputFiles("#archivo", muestra[1].path);
    visto = await esperarRespuesta(page, 60_000).catch(() => "(sin respuesta)");
  }
  console.log(`offline (${browserType.name()}): ${entradas} entries, «${visto}» (expected «${flores[muestra[1].python_pred]}»)`);
  await browser.close();
  return entradas > 0 && visto === flores[muestra[1].python_pred];
}

// WebKit's test camera shows a moving pattern, not a flower: the check is that
// the live viewfinder runs and a shot yields an answer (a "no flower" one).
async function porCamaraWebkit() {
  const browser = await webkit.launch();
  const ctx = await browser.newContext({ ...devices["iPhone 13"], permissions: ["camera"] });
  const page = await ctx.newPage();
  await page.goto(url);
  await page.click("#ir-camara");
  await page.click("#abrir-camara");
  await page.waitForSelector('#marco[data-modo="vivo"]', { timeout: 20_000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: join(out, "webkit-camara-visor.png") });
  await page.click("#disparar");
  const visto = await esperarRespuesta(page);
  console.log(`webkit camera: live viewfinder, answer «${visto}»`);
  await browser.close();
  return visto === "No veo ninguna flor";
}

// A hard case from the user's own photos: an orange gerbera that the model
// reads as sunflower 0.57, calendula 0.22, gerbera 0.11. It must not be
// captured outright, and the choice must offer the gerbera. Skipped where the
// photo is not on disk (it lives outside the repo, in ml/data/usuario).
const GERBERA = new URL("../../ml/data/usuario/gerbera_naranja_01.jpg", import.meta.url).pathname;
async function casoGerbera() {
  if (!existsSync(GERBERA)) {
    console.log("gerbera: photo not on disk, skipped");
    return true;
  }
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  await page.goto(url);
  await page.setInputFiles("#archivo", GERBERA);
  await page.waitForSelector('#dialogo:not([hidden])[data-escribiendo="false"]', { timeout: 120_000 });
  const texto = await page.textContent("#dialogo-texto");
  const opciones = await page.locator(".opcion-nombre").allTextContents();
  await page.screenshot({ path: join(out, "gerbera-duda.png") });
  await browser.close();
  console.log(`gerbera: «${texto}» options ${opciones.join(", ")}`);
  return texto.startsWith("No estoy seguro") && opciones.includes("Gerbera");
}

// WebKit stands in for iPhone Safari. It needs system libraries (sudo npx
// playwright install-deps webkit); without them the iPhone pass runs in
// Chromium with iPhone 13 emulation and the verdict says WEBKIT NOT RUN.
let webkitNoEjecutado = false;
const sinWebkit = (e) => {
  const m = String(e);
  if (!m.includes("missing dependencies") && !m.includes("shared libraries")) throw e;
  webkitNoEjecutado = true;
  console.log("WEBKIT NOT AVAILABLE on this system");
};

const sinRed = process.env.OFFLINE
  ? (await sinConexion(chromium)) &&
    (await sinConexion(webkit, devices["iPhone 13"]).catch((e) => (sinWebkit(e), true)))
  : true;
// SOLO_OFFLINE=1 runs just the offline check (with OFFLINE=1).
if (process.env.SOLO_OFFLINE) {
  const veredicto = !sinRed ? "FAIL" : webkitNoEjecutado ? "WEBKIT NOT RUN" : "OK";
  console.log(veredicto);
  process.exit(veredicto === "OK" ? 0 : veredicto === "FAIL" ? 1 : 2);
}
const chromiumRes = await porSubida("chromium", chromium, { viewport: { width: 1280, height: 900 } });
let movilRes;
try {
  movilRes = await porSubida("webkit-iphone", webkit, { ...devices["iPhone 13"] });
} catch (e) {
  sinWebkit(e);
  movilRes = await porSubida("chromium-iphone", chromium, { ...devices["iPhone 13"] });
}
const camaraOk = (await porCamara()) && (await porCamaraWebkit().catch((e) => (sinWebkit(e), true)));
const gerberaOk = await casoGerbera();
const n = muestra.length;
const ok =
  camaraOk && sinRed && gerberaOk &&
  [chromiumRes, movilRes].every((r) => r.errores === 0 && r.graves === 0 && r.decisiones === 0 && r.iguales >= 0.95 * n);
// A pass without WebKit is not a pass for iPhone: exit code 2.
const veredicto = !ok ? "FAIL" : webkitNoEjecutado ? "WEBKIT NOT RUN" : "OK";
console.log(veredicto);
process.exit(veredicto === "OK" ? 0 : veredicto === "FAIL" ? 1 : 2);
