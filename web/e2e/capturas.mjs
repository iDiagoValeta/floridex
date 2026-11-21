// Screenshots of the whole Floridex flow for review, in both views (normal
// and pixel): desktop in Chromium with a fake webcam, iPhone 13 in WebKit.
//
//   node e2e/capturas.mjs <dir with muestra.json and camara.y4m> <out dir> [url]
//
// The "unsure" step injects fixed candidates (the model still runs), as the
// end-to-end test does, because the sample photos are all clear cases.
// Headless Chromium sometimes paints a stale copy of the status box at the
// top of the WebGL viewfinder while it analyses; a real browser does not.
import { chromium, devices, webkit } from "playwright";
import { readFileSync } from "node:fs";

const [dir, out, urlArg] = process.argv.slice(2);
const url = urlArg ?? "http://localhost:5190/";
const m = JSON.parse(readFileSync(`${dir}/muestra.json`, "utf8"));
const fichas = JSON.parse(readFileSync(new URL("../../shared/floridex.json", import.meta.url), "utf8"));
const etiquetas = new Set(Object.values(JSON.parse(readFileSync(new URL("../../shared/modelo/config.json", import.meta.url), "utf8")).id2label));
const entradas = fichas.filter((f) => etiquetas.has(f.key));
const bien = (d) => m.filter((x) => x.dataset === d && x.key === x.python_pred);
const primera = bien("oxford102")[0];
const otras = ["swedish", "tf_flowers", "oxford17"].flatMap((d) => bien(d).slice(0, 2));
const sinFlor = bien("no_flor")[0];
const [dudaA, dudaB] = entradas.filter((f) => ![primera, ...otras].some((x) => x.key === f.key)).slice(8, 10);

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

const listo = (p) => p.waitForFunction(() => document.querySelectorAll("#lista li").length > 0);
const dialogo = (p) => p.waitForSelector('#dialogo:not([hidden])[data-escribiendo="false"]', { timeout: 120_000 });
const pausa = (p, ms = 400) => p.waitForTimeout(ms);

async function recorrido(p, prefijo, { camara }) {
  let n = 0;
  const foto = async (nombre) => {
    await pausa(p);
    await p.screenshot({ path: `${out}/${prefijo}-${String(++n).padStart(2, "0")}-${nombre}.png` });
  };
  await p.goto(url);
  await listo(p);
  await pausa(p, 1500);
  await foto("inicio-todo-sin-ver");
  await p.click("#ver-filtros");
  await foto("filtros");
  await p.click("#ver-filtros");

  // Identify: live camera on desktop, photo upload on iPhone.
  await p.click("#ir-camara");
  if (camara) {
    await p.click("#abrir-camara");
    await p.waitForSelector('#marco[data-modo="vivo"]');
    await pausa(p, 1200);
    await foto("camara-en-directo");
    await p.click("#disparar");
  } else {
    await foto("camara-cerrada");
    await p.setInputFiles("#archivo", primera.path);
  }
  await pausa(p, 500);
  await foto("analizando");
  await dialogo(p);
  await foto("capturada");
  await p.click(".boton-redondo.a");
  await foto("entrada-datos");
  for (const pg of ["campo", "saber", "foto"]) {
    await p.click(`#paginas [data-pagina="${pg}"]`);
    await foto(`entrada-${pg}`);
  }
  await p.click(".boton-redondo.b");

  // A few more captures, then the same flower again.
  for (const f of otras) {
    await p.setInputFiles("#archivo", f.path);
    await dialogo(p);
    await p.keyboard.press("Escape");
    await p.click(".boton-redondo.b");
  }
  await p.setInputFiles("#archivo", otras[0].path);
  await dialogo(p);
  await foto("ya-la-tenias");
  await p.keyboard.press("Escape");
  await p.click(".boton-redondo.b");

  // Unsure: the model offers candidates and the user picks.
  await p.evaluate((cs) => (window.__candidatos = cs), [
    { label: dudaA.key, score: 0.34 },
    { label: dudaB.key, score: 0.21 },
    { label: "no flower", score: 0.1 },
  ]);
  await p.setInputFiles("#archivo", otras[1].path);
  await dialogo(p);
  await foto("duda-cual-es");
  await p.keyboard.press("ArrowDown");
  await p.keyboard.press("ArrowDown");
  await p.keyboard.press("Enter");
  await p.click("#c-volver");

  // No flower.
  await p.setInputFiles("#archivo", sinFlor.path);
  await dialogo(p);
  await foto("sin-flor");
  await p.keyboard.press("Escape");
  await p.click("#c-volver");

  // The index with progress, a seen entry and an unseen one.
  await foto("lista-con-progreso");
  await p.click(`.fila[data-key="${dudaA.key}"]`);
  await foto("entrada-vista");
  await p.click(".boton-redondo.b");
  await p.click('.fila[data-estado="sinver"]');
  await foto("entrada-sin-ver");
  await p.click(".boton-redondo.b");
  await p.click("#ver-filtros");
  await p.selectOption("#f-estado", "capturada");
  await foto("filtro-capturadas");
  await p.click("#recuento .tecla");
  await p.click("#ver-filtros");

  // Pixel view.
  await p.click("#vista");
  await foto("pixel-lista");
  await p.click('.fila[data-estado="capturada"]');
  await foto("pixel-entrada");
  await p.click('#paginas [data-pagina="foto"]');
  await foto("pixel-tu-foto");
  await p.click(".boton-redondo.b");
  if (camara) {
    await p.click("#ir-camara");
    await p.click("#abrir-camara");
    await p.waitForSelector('#marco[data-modo="vivo"]');
    await pausa(p, 1200);
    await foto("pixel-camara");
    await p.click("#c-volver");
  }
  await p.click("#vista");
}

const cr = await chromium.launch({
  args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream", `--use-file-for-fake-video-capture=${dir}/camara.y4m`],
});
let ctx = await cr.newContext({ viewport: { width: 1440, height: 960 }, permissions: ["camera"] });
await ctx.addInitScript(falsearClasificador);
await recorrido(await ctx.newPage(), "web", { camara: true });
await cr.close();

const wk = await webkit.launch();
ctx = await wk.newContext({ ...devices["iPhone 13"] });
await ctx.addInitScript(falsearClasificador);
await recorrido(await ctx.newPage(), "iphone", { camara: false });
await wk.close();
console.log("ok");
