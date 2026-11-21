// End-to-end check of the Floridex (the collectible flower index).
//
//   node e2e/floridex.mjs <url> <dir with muestra.json> <out dir>
//
// Runs in Chromium (desktop) and WebKit (iPhone 13, Safari's engine): an
// index that starts unseen, capturing by photo, persistence across reload, a
// repeated capture, the "which one is it?" choice when the model is unsure
// (candidates become seen), declining it, search, filters, the cursor with
// keys and the on-screen pad, unseen and seen entries, and releasing a
// capture. Exits 1 on any failure.
import { chromium, devices, webkit } from "playwright";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const [url, dir, out] = process.argv.slice(2);
const muestra = JSON.parse(readFileSync(join(dir, "muestra.json"), "utf8"));
const fichas = JSON.parse(readFileSync(new URL("../../shared/floridex.json", import.meta.url), "utf8"));
const config = JSON.parse(readFileSync(new URL("../../shared/modelo/config.json", import.meta.url), "utf8"));
const etiquetas = new Set(Object.values(config.id2label));
// The index holds the cards of the flowers the shipped model knows.
const entradas = fichas.filter((f) => etiquetas.has(f.key));
const porClave = new Map(entradas.map((f) => [f.key, f]));
// Photos the Python model got right, of flowers with an entry.
const fotos = muestra.filter((m) => m.key === m.python_pred && porClave.has(m.key));
const tres = (n) => String(n).padStart(3, "0");

// Lets the test replace the next classification with fixed candidates, to
// drive the "unsure" path deterministically. The model still runs.
function falsearClasificador() {
  const W = window.Worker;
  window.Worker = class extends W {
    set onmessage(fn) {
      super.onmessage = (e) => {
        if (e.data?.tipo === "resultado" && window.__candidatos) {
          const candidatos = window.__candidatos;
          window.__candidatos = null;
          return fn({ data: { ...e.data, candidatos } });
        }
        return fn(e);
      };
    }
    get onmessage() {
      return super.onmessage;
    }
  };
}

async function probar(nombre, browserType, contexto) {
  const fallos = [];
  const ok = (cond, msg) => {
    if (!cond) fallos.push(msg);
  };
  const browser = await browserType.launch();
  const ctx = await browser.newContext(contexto);
  await ctx.addInitScript(falsearClasificador);
  const page = await ctx.newPage();
  const errores = [];
  page.on("pageerror", (e) => errores.push(String(e)));
  // transformers.js probes the optional tokenizer_config.json, which an image
  // model does not have; WebKit logs that 404 as a console error.
  page.on("console", (m) => m.type() === "error" && !m.text().includes("status of 404") && errores.push(m.text()));
  page.on("dialog", (d) => d.accept());

  const texto = (sel) => page.locator(sel).first().textContent();
  const visibles = () => page.locator("#lista li:not([hidden])").count();
  const cargar = async () => {
    await page.goto(url);
    await page.waitForFunction(() => document.getElementById("total").textContent !== "000" && document.querySelectorAll("#lista li").length > 0);
  };
  /** Waits for the text box to finish typing and returns its text. */
  const dialogo = async () => {
    await page.waitForSelector('#dialogo:not([hidden])[data-escribiendo="false"]', { timeout: 120_000 });
    return texto("#dialogo-texto");
  };
  const botonA = () => page.click(".boton-redondo.a");
  const botonB = () => page.click(".boton-redondo.b");
  const entradaDe = (v) =>
    page
      .waitForFunction((v) => !document.getElementById("p-entrada").hidden && document.getElementById("e-nombre").textContent === v, v, { timeout: 10_000 })
      .catch(() => {});
  const fila = (key) => page.locator(`.fila[data-key="${key}"]`);
  const subir = (path) => page.setInputFiles("#archivo", path);

  // 1. Everything starts unseen: numbered, no names, silhouettes only.
  await cargar();
  ok((await texto("#total")) === tres(entradas.length), `total ${await texto("#total")} != ${entradas.length}`);
  ok((await texto("#capturadas")) === "000" && (await texto("#vistas")) === "000", "starts with progress");
  ok((await page.locator("#lista li").count()) === entradas.length, "one row per entry");
  ok((await page.locator('.fila[data-estado="sinver"]').count()) === entradas.length, "every row unseen at start");
  ok((await page.locator(".fila-nombre").allTextContents()).every((t) => t === "?????"), "an unseen row shows a name");
  await page.waitForFunction((n) => document.querySelectorAll('.fila canvas[data-sprite$="|silueta"]').length === n, entradas.length);
  await page.screenshot({ path: join(out, `${nombre}-01-inicio.png`) });

  // 2. A clear photo captures its flower.
  const a = fotos[0];
  const fa = porClave.get(a.key);
  await page.click("#ir-camara");
  await subir(a.path);
  const t2 = await dialogo();
  ok(t2.startsWith(`¡Capturada! ${fa.nombre}`), `capture message: «${t2}»`);
  await page.screenshot({ path: join(out, `${nombre}-02-capturada.png`) });
  await botonA();
  await entradaDe(fa.nombre);
  ok((await texto("#e-sello")) === "¡Nueva!", "new stamp");
  ok((await texto("#e-num")) === `N.º ${tres(fa.numero)}`, "entry number");
  ok((await texto("#e-cientifico")) === fa.cientifico, "scientific name");
  ok((await texto("#e-descripcion")) === fa.descripcion, "description");
  ok((await page.locator("#e-toxicidad").isVisible()) === Boolean(fa.toxicidad), "toxicity warning");
  // The reference photo's author, linked to its source, whenever there is a photo.
  if (fa.credito) {
    const autoria = fa.credito.licencia === "cc0" && !fa.credito.autor.startsWith("(c)") ? "dominio público (CC0)" : fa.credito.autor;
    ok((await texto("#e-credito")) === `Foto: ${autoria}, iNaturalist`, `photo credit: «${await texto("#e-credito")}»`);
    ok((await page.locator("#e-credito a").getAttribute("href")) === fa.credito.fuente, "photo credit links to its source");
  } else ok(await page.locator("#e-credito").isHidden(), "a credit without a photo");
  await page.screenshot({ path: join(out, `${nombre}-03-entrada.png`) });
  await page.click('#paginas [data-pagina="campo"]');
  ok((await texto("#e-campo")).includes(fa.habitat), "field page");
  await page.keyboard.press("ArrowRight");
  const saberes = ["otros_nombres", "cuidados", "simbolismo", "etimologia", "usos"].filter((c) => (Array.isArray(fa[c]) ? fa[c].length : fa[c])).length;
  ok((await page.locator("#e-saber dd").count()) === saberes, "knowledge page shows every non-empty field");
  await page.keyboard.press("ArrowRight");
  ok((await texto("#e-hallazgo")).startsWith("Capturada el "), "capture date");
  ok((await page.locator("#e-foto img").getAttribute("src")).startsWith("data:image/jpeg"), "own photo, as taken");
  await page.screenshot({ path: join(out, `${nombre}-04-foto.png`) });
  await botonB();
  ok((await texto("#capturadas")) === "001" && (await texto("#vistas")) === "001", "counters after the first capture");
  ok((await fila(a.key).getAttribute("data-estado")) === "capturada", "row not captured");
  ok((await fila(a.key).locator("img.foto-flor").getAttribute("src")).endsWith(fa.foto ?? "data:"), "captured row shows the flower's photo as is");
  ok((await page.locator("#e-figura canvas, .fila:not([data-estado='capturada']) img").count()) === 0, "a photo leaks into an uncaptured row");

  // 2b. Pixel view: the captured flower turns into its sprite, the photo into greens; it is remembered.
  await page.click("#vista");
  ok((await page.locator("#vista").getAttribute("aria-pressed")) === "true", "view switch state");
  ok((await fila(a.key).locator("canvas").getAttribute("data-sprite")).endsWith(fa.sprite ? "|color" : "|pixel"), "pixel view of a captured row");
  ok((await fila(a.key).locator("img").count()) === 0, "pixel view still shows the photo");
  await page.screenshot({ path: join(out, `${nombre}-04b-pixel.png`) });

  // 3. It survives a reload, view included.
  await cargar();
  ok((await page.locator("#vista").getAttribute("aria-pressed")) === "true", "view not remembered");
  await fila(a.key).click();
  await entradaDe(fa.nombre);
  await page.click('#paginas [data-pagina="foto"]');
  ok((await page.locator("#e-foto canvas").getAttribute("data-sprite"))?.endsWith("|pixel"), "own photo in pixel view");
  await page.click("#vista");
  ok((await page.locator("#e-foto img").getAttribute("src")).startsWith("data:image/jpeg"), "back to the photo as taken, on the same page");
  await botonB();
  ok((await texto("#capturadas")) === "001", "capture lost on reload");
  ok((await fila(a.key).locator(".fila-nombre").textContent()) === fa.nombre, "captured row shows its name after reload");
  await fila(a.key).click();
  await entradaDe(fa.nombre);
  ok((await texto("#e-nombre")) === fa.nombre, "entry after reload");
  await page.keyboard.press("Escape");

  // 4. The same flower again does not count twice.
  await page.click("#ir-camara");
  await subir(a.path);
  ok((await dialogo()) === `${fa.nombre} ya estaba en tu Floridex.`, "repeat message");
  await botonA();
  await entradaDe(fa.nombre);
  ok((await texto("#e-sello")) === "Ya la tenías", "repeat stamp");
  await botonB();
  ok((await texto("#capturadas")) === "001", "a repeated capture counted twice");

  // 5. Unsure: the candidates become seen and the user picks one.
  const [b, c, d] = entradas.filter((f) => f.key !== a.key).slice(5, 8);
  await page.evaluate((cs) => (window.__candidatos = cs), [
    { label: b.key, score: 0.31 },
    { label: c.key, score: 0.22 },
    { label: "no flower", score: 0.12 },
  ]);
  await page.click("#ir-camara");
  await subir(fotos[1].path);
  ok((await dialogo()) === "No estoy seguro. ¿Cuál de estas es?", "unsure message");
  ok((await page.locator(".opcion").count()) === 3, "two candidates (no-flower class excluded) plus «Ninguna»");
  ok((await texto("#vistas")) === "003", `seen counter with candidates: ${await texto("#vistas")}`);
  await page.screenshot({ path: join(out, `${nombre}-05-duda.png`) });
  await page.click(`.opcion[data-key="${b.key}"]`);
  ok((await dialogo()).startsWith(`¡Capturada! ${b.nombre}`), "picked candidate captured");
  await botonA();
  await entradaDe(b.nombre);
  await page.click('#paginas [data-pagina="foto"]');
  ok((await texto("#e-hallazgo")).includes("elegida por ti"), "picked capture says so");
  await botonB();
  ok((await texto("#capturadas")) === "002" && (await texto("#vistas")) === "003", "counters after picking");
  ok((await fila(c.key).getAttribute("data-estado")) === "vista", "unpicked candidate seen");

  // 6. Declining: «Ninguna» captures nothing, but the candidate was seen.
  await page.evaluate((k) => (window.__candidatos = [{ label: k, score: 0.4 }]), d.key);
  await page.click("#ir-camara");
  await subir(fotos[2].path);
  await dialogo();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await page.waitForSelector("#dialogo", { state: "hidden" });
  ok((await texto("#capturadas")) === "002" && (await texto("#vistas")) === "004", "declining");
  await page.click("#c-volver");

  // 7. A seen entry shows name and silhouette; an unseen one only clues.
  await fila(c.key).click();
  await entradaDe(c.nombre);
  ok((await texto("#e-estado")).startsWith("La has visto"), "seen entry");
  ok(await page.locator("#paginas").isHidden(), "seen entry has pages");
  ok((await page.locator("#e-figura canvas").getAttribute("data-sprite")).endsWith("|silueta"), "seen entry shows the flower");
  await page.screenshot({ path: join(out, `${nombre}-06-vista.png`) });
  await botonB();
  const sv = entradas.find((f) => ![a.key, b.key, c.key, d.key].includes(f.key));
  await fila(sv.key).click();
  await page.waitForFunction(() => !document.getElementById("p-entrada").hidden);
  ok((await texto("#e-nombre")) === "?????", "unseen entry reveals the name");
  ok((await texto("#e-pistas")).includes(sv.familia), "unseen entry clues");
  // Its silhouette is drawn from the photo: the photo's credit is there too.
  ok((await page.locator("#e-credito").isVisible()) === Boolean(sv.credito), "credit next to a silhouette");
  await botonB();
  // A flower without a licensed photo: generic pixel flower, no credit.
  const sinFoto = entradas.find((f) => !f.foto);
  if (sinFoto) {
    await fila(sinFoto.key).click();
    await page.waitForFunction(() => !document.getElementById("p-entrada").hidden);
    ok(await page.locator("#e-credito").isHidden(), "a credit for a flower without a photo");
    ok((await page.locator("#e-figura canvas").getAttribute("data-sprite")) === "null|silueta", "a flower without a photo shows the generic flower");
  }
  await botonB();

  // 8. Search and filters.
  await page.click("#ver-filtros");
  await page.selectOption("#f-estado", "capturada");
  ok((await visibles()) === 2, `captured filter: ${await visibles()}`);
  await page.selectOption("#f-estado", "vista");
  ok((await visibles()) === 2, "seen filter");
  await page.selectOption("#f-estado", "sinver");
  ok((await visibles()) === entradas.length - 4, "unseen filter");
  await page.selectOption("#f-estado", "");
  await page.fill("#buscador", fa.nombre.toUpperCase());
  ok((await page.locator(`#lista li:not([hidden]) .fila[data-key="${a.key}"]`).count()) === 1, "search by name");
  await page.fill("#buscador", sv.nombre);
  ok((await page.locator(`#lista li:not([hidden]) .fila[data-key="${sv.key}"]`).count()) === 0, "search reveals an unseen name");
  await page.fill("#buscador", String(sv.numero));
  ok((await visibles()) === 1, "search by number");
  await page.fill("#buscador", "");
  await page.selectOption("#f-familia", fa.familia);
  ok((await visibles()) === entradas.filter((f) => f.familia === fa.familia).length, "family filter");
  await page.selectOption("#f-familia", "");
  await page.selectOption("#f-grupo", "silvestre");
  await page.selectOption("#f-estacion", "invierno");
  ok((await visibles()) === entradas.filter((f) => f.grupo === "silvestre" && f.estaciones.includes("invierno")).length, "group plus season");
  await page.selectOption("#f-grupo", "");
  await page.selectOption("#f-estacion", "");
  await page.selectOption("#f-color", "amarillo");
  ok((await visibles()) === entradas.filter((f) => f.color === "amarillo").length, "colour filter");
  await page.screenshot({ path: join(out, `${nombre}-07-filtros.png`) });
  await page.click("#recuento .tecla");
  ok((await visibles()) === entradas.length, "clearing filters");
  await page.click("#ver-filtros");

  // 9. Cursor: keys and the on-screen pad.
  await page.click(".cruz.abajo");
  await page.click(".cruz.abajo");
  await page.keyboard.press("ArrowUp");
  ok((await page.locator('.fila[aria-current="true"]').getAttribute("data-key")) === entradas[1].key, "cursor");
  await page.keyboard.press("Enter");
  ok(await page.locator("#p-entrada").isVisible(), "A opens the entry under the cursor");
  await page.keyboard.press("Escape");

  // 10. Releasing a capture leaves the flower seen.
  await fila(a.key).click();
  await entradaDe(fa.nombre);
  await page.click('#paginas [data-pagina="foto"]');
  await page.click("#olvidar");
  await page.waitForFunction(() => document.getElementById("p-entrada").dataset.estado === "vista");
  await botonB();
  ok((await texto("#capturadas")) === "001" && (await texto("#vistas")) === "004", "counters after releasing");
  await page.screenshot({ path: join(out, `${nombre}-08-lista.png`) });

  ok(errores.length === 0, `console errors: ${errores.join(" | ")}`);
  await browser.close();
  console.log(`${nombre}: ${fallos.length ? `FAIL\n  ${fallos.join("\n  ")}` : "OK"}`);
  return fallos.length === 0;
}

let todo = await probar("chromium", chromium, { viewport: { width: 1280, height: 900 } });
// WebKit stands in for iPhone Safari. Without its system libraries the iPhone
// pass runs in Chromium with iPhone 13 emulation and the verdict says so.
let webkitNoEjecutado = false;
try {
  todo = (await probar("webkit-iphone", webkit, { ...devices["iPhone 13"] })) && todo;
} catch (e) {
  if (!String(e).includes("missing dependencies") && !String(e).includes("shared libraries")) throw e;
  webkitNoEjecutado = true;
  console.log("WEBKIT NOT AVAILABLE on this system: running Chromium with iPhone 13 emulation");
  todo = (await probar("chromium-iphone", chromium, { ...devices["iPhone 13"] })) && todo;
}
// A pass without WebKit is not a pass for iPhone: exit code 2.
const veredicto = !todo ? "FAIL" : webkitNoEjecutado ? "WEBKIT NOT RUN" : "OK";
console.log(veredicto);
process.exit(veredicto === "OK" ? 0 : veredicto === "FAIL" ? 1 : 2);
