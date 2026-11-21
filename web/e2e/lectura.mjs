// Regression check: a reading must end however the user leaves its answer.
// Bug reported on a phone: after a "no flower" answer the still photo stayed
// on screen and no new photo could be taken until the app was closed.
//
//   node e2e/lectura.mjs <url> <dir with muestra.json and camara.y4m>
//
// For each way out of a "no flower" answer (the «Cámara» pill, button B,
// «‹ Lista» and back, a new «Foto»), the viewfinder must be live again (or
// the closed lens, without a camera) and a new shot or upload must be read.
// Chromium runs with a fake webcam; WebKit (iPhone 13) with uploads only.
import { chromium, devices, webkit } from "playwright";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const [url, dir] = process.argv.slice(2);
const muestra = JSON.parse(readFileSync(join(dir, "muestra.json"), "utf8"));
const sinFlor = muestra.find((m) => m.dataset === "no_flor" && m.python_pred === "no flower").path;
const flor = muestra.find((m) => m.python_pred !== "no flower" && m.python_top3?.[0][1] >= 0.9).path;
const SALIDAS = ["pastilla Cámara", "botón B", "Lista y vuelta", "nueva Foto", "Cámara durante el análisis"];

async function probar(nombre, browserType, contexto, conCamara) {
  const fallos = [];
  const browser = await browserType.launch(
    conCamara
      ? { args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream", `--use-file-for-fake-video-capture=${join(dir, "camara.y4m")}`] }
      : {},
  );
  const ctx = await browser.newContext({ ...contexto, ...(conCamara ? { permissions: ["camera"] } : {}) });
  const page = await ctx.newPage();
  const respuesta = async () => {
    await page.waitForSelector('#dialogo:not([hidden])[data-escribiendo="false"]', { timeout: 60_000 });
    return page.textContent("#dialogo-texto");
  };
  const camaraLista = async () => {
    if ((await page.getAttribute("#lcd", "data-pantalla")) !== "camara") await page.click("#ir-camara");
    if (conCamara && (await page.getAttribute("#marco", "data-modo")) !== "vivo") {
      await page.click("#abrir-camara", { timeout: 5000 });
      await page.waitForSelector('#marco[data-modo="vivo"]', { timeout: 10_000 });
    }
  };
  await page.goto(url);
  await page.waitForFunction(() => document.querySelectorAll("#lista li").length > 0);
  for (const salida of SALIDAS) {
    try {
      await ronda(salida);
    } catch (e) {
      // Stuck: the next round starts from a fresh page.
      fallos.push(`${salida}: stuck (${String(e).split("\n")[0]})`);
      console.log(`  [${nombre}] ${salida}: STUCK`);
      await page.reload();
      await page.waitForFunction(() => document.querySelectorAll("#lista li").length > 0);
    }
  }
  async function ronda(salida) {
    await camaraLista();
    await page.setInputFiles("#archivo", sinFlor);
    if (salida === "Cámara durante el análisis") {
      // Leaving while the photo is still being read: its answer must never show up.
      await page.waitForFunction(() => document.getElementById("estado").textContent.startsWith("Analizando"));
      await page.click("#ir-camara");
      await page.waitForTimeout(2500);
      if (await page.locator("#dialogo").isVisible()) fallos.push(`${salida}: the abandoned reading still answered`);
    } else {
      const t = await respuesta();
      if (!t.startsWith("No veo ninguna flor")) fallos.push(`${salida}: expected a no-flower answer, got «${t}»`);
    }
    if (salida === "pastilla Cámara") await page.click("#ir-camara");
    else if (salida === "botón B") await page.click(".boton-redondo.b");
    else if (salida === "Lista y vuelta") {
      await page.click(".boton-redondo.b");
      await page.click("#c-volver", { timeout: 5000 });
      await page.click("#ir-camara", { timeout: 5000 });
      if (conCamara) await page.click("#abrir-camara", { timeout: 5000 });
    }
    let leida;
    if (salida === "nueva Foto") {
      await page.setInputFiles("#archivo", flor);
      leida = await respuesta().catch(() => null);
    } else {
      const modo = await page.waitForFunction((v) => document.getElementById("marco").dataset.modo === v, conCamara ? "vivo" : "cerrado", { timeout: 5000 })
        .then(() => true)
        .catch(() => false);
      const estado = await page.evaluate(() => [document.getElementById("marco").dataset.modo, document.getElementById("disparar").disabled]);
      if (!modo) fallos.push(`${salida}: viewfinder still «${estado[0]}» (shutter disabled: ${estado[1]})`);
      if (conCamara) {
        if (await page.locator("#disparar").isEnabled()) {
          await page.click("#disparar");
          leida = await respuesta().catch(() => null);
        }
      } else {
        await page.setInputFiles("#archivo", flor);
        leida = await respuesta().catch(() => null);
      }
    }
    if (!leida) fallos.push(`${salida}: the next photo was not read`);
    console.log(`  [${nombre}] ${salida}: ${leida ? `next photo read («${leida.slice(0, 40)}»)` : "NOT READ"}`);
    // Leave the answer the way a person would, to start the next round clean.
    if (await page.locator("#dialogo").isVisible()) await page.keyboard.press("Escape");
  }
  await browser.close();
  console.log(`${nombre}: ${fallos.length ? `FAIL\n  ${fallos.join("\n  ")}` : "OK"}`);
  return fallos.length === 0;
}

let todo = await probar("chromium", chromium, { viewport: { width: 1280, height: 900 } }, true);
let webkitNoEjecutado = false;
try {
  todo = (await probar("webkit-iphone", webkit, { ...devices["iPhone 13"] }, false)) && todo;
} catch (e) {
  if (!String(e).includes("missing dependencies") && !String(e).includes("shared libraries")) throw e;
  webkitNoEjecutado = true;
  console.log("WEBKIT NOT AVAILABLE on this system");
}
const veredicto = !todo ? "FAIL" : webkitNoEjecutado ? "WEBKIT NOT RUN" : "OK";
console.log(veredicto);
process.exit(veredicto === "OK" ? 0 : veredicto === "FAIL" ? 1 : 2);
