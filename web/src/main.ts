import "@fontsource/pixelify-sans/400.css";
import "@fontsource/pixelify-sans/600.css";
import "./estilo.css";
import { Visor, VisorPlano, type VisorComun } from "./visor";
import type { Mensaje } from "./clasificador.worker";
import * as almacen from "./almacen";
import type { Captura } from "./almacen";
import { pintarFotoPixel, pintarSilueta, pintarSprite } from "./pixel";
import { activo as sonidoActivo, alternar as alternarSonido, sonidos } from "./sonido";

// One entry of floridex.json, built by shared/floridex.py.
type Flor = {
  numero: number;
  key: string;
  nombre: string;
  otros_nombres: string[];
  cientifico: string;
  familia: string;
  descripcion: string;
  habitat: string;
  floracion: string;
  curiosidad: string;
  toxicidad: string | null;
  cuidados: string | null;
  simbolismo: string | null;
  etimologia: string | null;
  usos: string | null;
  grupo: "jardin" | "silvestre";
  colores: [string, string];
  estaciones: string[];
  color: string;
  foto: string | null;
  sprite: string | null;
  /** Author and licence of the reference photo, shown wherever it or its sprite appears. */
  credito: { autor: string; licencia: string; fuente: string } | null;
};
type Candidato = { label: string; score: number };
type Estado = "capturada" | "vista" | "sinver";
type Pantalla = "lista" | "entrada" | "camara";
type Pagina = "datos" | "campo" | "saber" | "foto";

// The model is calibrated: its confidence tracks how often it is right. At
// 0.7 or more it captures directly (about 89 % of photos, 95 % right);
// below, the user picks among the top candidates. Hard look-alikes (an orange
// gerbera read as sunflower 0.57, calendula 0.22, gerbera 0.11) land here.
const UMBRAL_DUDA = 0.7;
// Candidates offered when unsure, the model's top ones.
const MAX_CANDIDATAS = 3;
// The model's reject class: photos with no flower in them. It never gets an entry.
const NO_FLOR = "no flower";
const PAGINAS: Pagina[] = ["datos", "campo", "saber", "foto"];
const ESTACION: Record<string, string> = { primavera: "Primavera", verano: "Verano", otoño: "Otoño", invierno: "Invierno" };
const COLOR: Record<string, string> = {
  blanco: "Blanco", amarillo: "Amarillo", naranja: "Naranja", rojo: "Rojo", rosa: "Rosa", morado: "Morado", azul: "Azul", verde: "Verde",
};
const lento = matchMedia("(prefers-reduced-motion: reduce)").matches;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const lcd = $("lcd");
const marco = $("marco");
const estado = $("estado");
const disparar = $<HTMLButtonElement>("disparar");
const girar = $<HTMLButtonElement>("girar");
const lista = $("lista");
const buscador = $<HTMLInputElement>("buscador");
const filtros = {
  estado: $<HTMLSelectElement>("f-estado"),
  familia: $<HTMLSelectElement>("f-familia"),
  grupo: $<HTMLSelectElement>("f-grupo"),
  estacion: $<HTMLSelectElement>("f-estacion"),
  color: $<HTMLSelectElement>("f-color"),
};
// Without WebGL the viewfinder falls back to a plain 2D canvas (no pixel view
// of the camera) instead of taking the whole app down.
const visor: VisorComun = (() => {
  try {
    return new Visor($<HTMLCanvasElement>("lienzo"));
  } catch (err) {
    console.warn("Floridex: sin WebGL, visor sencillo", err);
    return new VisorPlano($<HTMLCanvasElement>("lienzo"));
  }
})();
const video = document.createElement("video");
video.playsInline = true;
video.muted = true;
video.setAttribute("playsinline", "");
video.setAttribute("aria-hidden", "true");
// In the document, behind the canvas: iOS Safari is most reliable with a
// visible, inline video element as the WebGL source.
video.className = "visor-video";
marco.prepend(video);

/** Entries: cards of the flowers the loaded model can recognise, by number. */
let entradas: Flor[] = [];
const flores = new Map<string, Flor>();
const capturas = new Map<string, Captura>();
const vistas = new Set<string>();
const filas = new Map<string, HTMLLIElement>();
let visibles: Flor[] = [];
let cursor = 0;
let abierta: string | null = null;
let pagina: Pagina = "datos";
let stream: MediaStream | null = null;
let frontal = false;

// Offline: only in production builds, the dev server must always be live.
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(() => {});
}

const estadoDe = (key: string): Estado => (capturas.has(key) ? "capturada" : vistas.has(key) ? "vista" : "sinver");
const numero = (n: number) => String(n).padStart(3, "0");
const normal = (s: string) => s.normalize("NFD").replace(/\p{Diacritic}/gu, "").toLowerCase();
const fechaLarga = (iso: string) => new Date(iso).toLocaleDateString("es-ES", { day: "numeric", month: "long", year: "numeric" });
// A rounded "100 %" would claim certainty the model does not have.
const porcentaje = (p: number) =>
  p >= 0.995 ? "más del 99 %" : new Intl.NumberFormat("es-ES", { style: "percent", maximumFractionDigits: 0 }).format(p);
const spriteDe = (f: Flor) => (f.sprite ? `${import.meta.env.BASE_URL}${f.sprite}` : null);

// The two views of photos: "normal" shows them as they are, "pixel" as pixel
// art in the screen's greens. The choice is kept on this device.
const CLAVE_VISTA = "floridex:vista";
let vistaPixel = (() => {
  try {
    return localStorage.getItem(CLAVE_VISTA) === "pixel";
  } catch {
    return false;
  }
})();
visor.pixel = vistaPixel;

/**
 * The picture of a flower. Captured, normal view: a photo as it is, its
 * reference photo or else the user's own. Captured, pixel view: its sprite, or
 * that photo printed in greens. Not captured: its pixel silhouette.
 */
function figura(f: Flor, revelar = false): HTMLElement {
  const cap = capturas.get(f.key);
  // `revelar`: the candidates to pick from show the flower itself, captured or
  // not, or the choice would be between silhouettes.
  if ((cap || (revelar && f.foto)) && !vistaPixel) {
    const img = new Image();
    img.src = f.foto ? `${import.meta.env.BASE_URL}${f.foto}` : cap!.foto;
    img.alt = "";
    img.className = "foto-flor";
    img.decoding = "async";
    img.loading = "lazy";
    return img;
  }
  const c = document.createElement("canvas");
  c.className = "sprite";
  c.setAttribute("aria-hidden", "true");
  const sprite = spriteDe(f);
  if (!cap && !revelar) pintarSilueta(c, sprite);
  else if (sprite) pintarSprite(c, sprite);
  else if (cap) pintarFotoPixel(c, cap.foto, 48, true);
  else pintarSilueta(c, sprite);
  return c;
}

/** CC0 photos come without an author's name ("no rights reserved"): say so plainly. */
function autoria(c: { autor: string; licencia: string }) {
  return c.licencia === "cc0" && !c.autor.startsWith("(c)") ? "dominio público (CC0)" : c.autor;
}

// Screens ------------------------------------------------------------------------
function ir(p: Pantalla) {
  // An answer belongs to the photo it was about: any change of screen, and
  // every entry to the camera screen, ends the reading.
  if (p !== lcd.dataset.pantalla || p === "camara") {
    cerrarDialogo(false);
    terminarLectura();
  }
  lcd.dataset.pantalla = p;
  $("p-lista").hidden = p !== "lista";
  $("p-entrada").hidden = p !== "entrada";
  $("p-camara").hidden = p !== "camara";
  if (p !== "camara") apagarCamara();
  if (p === "lista") moverCursor(0, false);
}

// Classifier worker ---------------------------------------------------------------
const worker = new Worker(new URL("./clasificador.worker.ts", import.meta.url), { type: "module" });
let modeloListo = false;
let siguienteId = 0;
const peticiones = new Map<number, { ok: (c: Candidato[]) => void; ko: (e: Error) => void }>();

worker.onmessage = (e: MessageEvent<Mensaje>) => {
  const m = e.data;
  if (m.tipo === "progreso") {
    if (!modeloListo) estado.textContent = m.fraccion < 1 ? `Cargando el Floridex… ${Math.round(m.fraccion * 100)} %` : "Cargando el Floridex…";
  } else if (m.tipo === "listo") {
    modeloListo = true;
    if (estado.textContent?.startsWith("Cargando")) estado.textContent = "";
  } else if (m.tipo === "resultado") {
    peticiones.get(m.id)?.ok(m.candidatos);
    peticiones.delete(m.id);
  } else if (m.tipo === "error") {
    if (m.id !== undefined) {
      peticiones.get(m.id)?.ko(new Error(m.mensaje));
      peticiones.delete(m.id);
    } else {
      console.error("Floridex: el modelo no cargó:", m.mensaje);
      estado.textContent = "No he podido cargar el modelo. Recarga la página para intentarlo de nuevo.";
    }
  }
};
// Safari before 26 without WebGPU needs ONNX Runtime's plain build, not the
// asyncify one (transformers.js makes the same call; workers cannot see navigator.vendor).
const ua = navigator.userAgent;
const safari = navigator.vendor.includes("Apple") && !/CriOS|FxiOS|EdgiOS|OPiOS|Chrome|Android/.test(ua);
const safariAntiguo = safari && Number(ua.match(/Version\/(\d+)/)?.[1] ?? 99) < 26;
// transformers.js caches the model by URL: when the shipped weights change
// (a retrained model at the same path), drop that cache before loading, or the
// old model would be used forever.
declare const __HUELLA_MODELO__: string;
async function renovarModelo() {
  try {
    const clave = "floridex:modelo";
    if (localStorage.getItem(clave) === __HUELLA_MODELO__) return;
    await caches.delete("transformers-cache");
    localStorage.setItem(clave, __HUELLA_MODELO__);
  } catch {
    // No localStorage or Cache Storage (private mode): nothing cached to renew.
  }
}
renovarModelo().then(() =>
  worker.postMessage({
    tipo: "cargar",
    raiz: new URL(import.meta.env.BASE_URL, document.baseURI).href,
    asyncify: !(safariAntiguo && !("gpu" in navigator)),
  }),
);

/**
 * The worker gets the photo untouched: the file as uploaded, or the camera
 * frame as pixels. Re-encoding it (as JPEG through a canvas) was enough to
 * change the answer on small photos.
 */
function clasificar(imagen: Blob | ImageBitmap): Promise<Candidato[]> {
  const id = siguienteId++;
  return new Promise((ok, ko) => {
    peticiones.set(id, { ok, ko });
    worker.postMessage({ tipo: "clasificar", id, imagen }, imagen instanceof Blob ? [] : [imagen]);
  });
}

// Data: cards, the model's labels and the user's progress ----------------------------
const base = import.meta.env.BASE_URL;
const listo = Promise.all([
  fetch(`${base}floridex.json`).then((r) => r.json() as Promise<Flor[]>),
  // The index follows the model: swapping in a model that knows more flowers
  // (with its config.json) grows the Floridex with no code change.
  fetch(`${base}models/floridex/config.json`).then((r) => r.json() as Promise<{ id2label: Record<string, string> }>),
  almacen.capturas().catch(() => [] as Captura[]),
  almacen.vistas().catch(() => []),
]).then(async ([fichas, config, guardadas, vistasGuardadas]) => {
  const etiquetas = new Set(Object.values(config.id2label));
  entradas = fichas.filter((f) => etiquetas.has(f.key)).sort((a, b) => a.numero - b.numero);
  for (const f of entradas) flores.set(f.key, f);
  for (const c of guardadas) if (flores.has(c.key)) capturas.set(c.key, c);
  for (const v of vistasGuardadas) if (flores.has(v.key)) vistas.add(v.key);
  $("cuenta-flores").textContent = String(entradas.length);
  pintarFamilias();
  pintarLista();
  contar();
});

function contar() {
  const n = capturas.size;
  const total = entradas.length;
  const vistasTotal = new Set([...vistas, ...capturas.keys()]).size;
  $("vistas").textContent = numero(vistasTotal);
  $("capturadas").textContent = numero(n);
  $("total").textContent = numero(total);
  const p = $("progreso");
  p.setAttribute("aria-valuenow", String(n));
  p.setAttribute("aria-valuemax", String(total));
  // The bar fills in whole blocks, like an old screen.
  $("progreso-barra").style.width = total ? `${Math.ceil((n / total) * 40) * 2.5}%` : "0";
}

// List ---------------------------------------------------------------------------
// Captured mark: a tiny pixel flower.
const MARCA = `<svg class="marca-capturada" viewBox="0 0 5 5" aria-hidden="true"><path d="M2 0h1v1H2zM0 2h1v1H0zM4 2h1v1H4zM2 4h1v1H2zM1 1h3v3H1z"/><path class="marca-centro" d="M2 2h1v1H2z"/></svg>`;

function pintarFila(li: HTMLLIElement, f: Flor) {
  const b = li.firstElementChild as HTMLButtonElement;
  const e = estadoDe(f.key);
  b.dataset.estado = e;
  b.setAttribute("aria-label", e === "sinver" ? `Número ${numero(f.numero)}, sin ver` : `Número ${numero(f.numero)}, ${f.nombre}, ${e}`);
  b.querySelector(".fila-figura")!.replaceChildren(figura(f));
  b.querySelector(".fila-nombre")!.textContent = e === "sinver" ? "?????" : f.nombre;
  b.querySelector(".fila-marca")!.innerHTML = e === "capturada" ? MARCA : "";
}

function pintarLista() {
  filas.clear();
  lista.replaceChildren(
    ...entradas.map((f) => {
      const li = document.createElement("li");
      const b = document.createElement("button");
      b.type = "button";
      b.className = "fila";
      b.dataset.key = f.key;
      b.innerHTML = `<span class="fila-cursor" aria-hidden="true">▶</span><span class="fila-num">${numero(f.numero)}</span><span class="figura fila-figura"></span><span class="fila-nombre"></span><span class="fila-marca"></span>`;
      b.addEventListener("click", () => {
        const i = visibles.findIndex((v) => v.key === f.key);
        if (i >= 0) cursor = i;
        abrirEntrada(f.key);
      });
      li.append(b);
      pintarFila(li, f);
      filas.set(f.key, li);
      return li;
    }),
  );
  filtrar();
}

function pintarFamilias() {
  const familias = new Map<string, number>();
  for (const f of entradas) familias.set(f.familia, (familias.get(f.familia) ?? 0) + 1);
  filtros.familia.replaceChildren(
    new Option("Familia", ""),
    ...[...familias].sort(([a], [b]) => a.localeCompare(b)).map(([fam, n]) => new Option(`${fam} (${n})`, fam)),
  );
}

function filtrar() {
  const q = normal(buscador.value.trim());
  const porNumero = /^\d+$/.test(q) ? Number(q) : null;
  visibles = entradas.filter((f) => {
    if (filtros.estado.value && estadoDe(f.key) !== filtros.estado.value) return false;
    if (filtros.familia.value && f.familia !== filtros.familia.value) return false;
    if (filtros.grupo.value && f.grupo !== filtros.grupo.value) return false;
    if (filtros.estacion.value && !f.estaciones.includes(filtros.estacion.value)) return false;
    if (filtros.color.value && f.color !== filtros.color.value) return false;
    if (!q) return true;
    if (porNumero !== null) return f.numero === porNumero;
    // Names of flowers never seen are not searchable: no spoilers.
    if (estadoDe(f.key) === "sinver") return false;
    return normal(`${f.nombre} ${f.cientifico} ${f.otros_nombres.join(" ")}`).includes(q);
  });
  const claves = new Set(visibles.map((f) => f.key));
  for (const [k, li] of filas) li.hidden = !claves.has(k);
  const n = visibles.length;
  const filtrado = n !== entradas.length;
  const recuento = $("recuento");
  recuento.replaceChildren(n ? `${n} ${n === 1 ? "entrada" : "entradas"}` : "Ninguna entrada coincide.");
  if (filtrado) {
    const limpiar = document.createElement("button");
    limpiar.type = "button";
    limpiar.className = "tecla tecla-mini";
    limpiar.textContent = "Quitar filtros";
    limpiar.addEventListener("click", () => {
      buscador.value = "";
      for (const s of Object.values(filtros)) s.value = "";
      filtrar();
    });
    recuento.append(" ", limpiar);
  }
  cursor = Math.min(cursor, Math.max(0, n - 1));
  moverCursor(0, false);
}
for (const s of Object.values(filtros)) s.addEventListener("change", filtrar);
buscador.addEventListener("input", filtrar);
$("ver-filtros").addEventListener("click", () => {
  const abrir = $("filtros").hidden;
  $("filtros").hidden = !abrir;
  $("ver-filtros").setAttribute("aria-expanded", String(abrir));
  sonidos.cursor();
});

function moverCursor(paso: number, sonar = true) {
  if (!visibles.length) {
    pintarPrevia(null);
    return;
  }
  const antes = cursor;
  cursor = Math.min(visibles.length - 1, Math.max(0, cursor + paso));
  for (const li of lista.querySelectorAll(".fila[aria-current]")) li.removeAttribute("aria-current");
  const f = visibles[cursor];
  const fila = filas.get(f.key)!.firstElementChild as HTMLElement;
  fila.setAttribute("aria-current", "true");
  if (paso !== 0) fila.scrollIntoView({ block: "nearest" });
  if (sonar && antes !== cursor) sonidos.cursor();
  pintarPrevia(f);
}

function pintarPrevia(f: Flor | null) {
  const e = f ? estadoDe(f.key) : "sinver";
  $("previa-figura").replaceChildren(...(f ? [figura(f)] : []));
  $("previa-num").textContent = f ? `N.º ${numero(f.numero)}` : "";
  $("previa-nombre").textContent = f ? (e === "sinver" ? "?????" : f.nombre) : "";
}

// Entry ----------------------------------------------------------------------------
function dato(dl: HTMLElement, titulo: string, valor: string | null) {
  if (!valor) return;
  const d = document.createElement("div");
  const dt = document.createElement("dt");
  dt.textContent = titulo;
  const dd = document.createElement("dd");
  dd.textContent = valor;
  d.append(dt, dd);
  dl.append(d);
}

const enLista = (xs: string[]) => new Intl.ListFormat("es", { type: "conjunction" }).format(xs);

/** `sello`: "nueva" right after a first capture, "repetida" when it was already captured. */
let selloAbierto: "nueva" | "repetida" | undefined;
function abrirEntrada(key: string, sello?: "nueva" | "repetida") {
  const f = flores.get(key);
  if (!f) return;
  selloAbierto = sello;
  if (lcd.dataset.pantalla !== "entrada") sonidos.abrir();
  abierta = key;
  const e = estadoDe(key);
  const cap = capturas.get(key);
  const p = $("p-entrada");
  p.dataset.estado = e;
  $("e-num").textContent = `N.º ${numero(f.numero)}`;
  const s = $("e-sello");
  s.hidden = !sello;
  s.textContent = sello === "nueva" ? "¡Nueva!" : sello === "repetida" ? "Ya la tenías" : "";

  $("e-figura").replaceChildren(figura(f));
  $("e-nombre").textContent = e === "sinver" ? "?????" : f.nombre;
  // The picture is the reference photo or drawn from it (sprite, silhouette): credit its author.
  const credito = $("e-credito");
  credito.hidden = !f.credito;
  if (f.credito) {
    const a = document.createElement("a");
    a.href = f.credito.fuente;
    a.target = "_blank";
    a.rel = "noopener";
    a.textContent = autoria(f.credito);
    credito.replaceChildren("Foto: ", a, ", iNaturalist");
  }
  $("e-cientifico").textContent = e === "capturada" ? f.cientifico : "";
  const tipos = e === "capturada" ? [f.grupo === "jardin" ? "Jardín" : "Silvestre", COLOR[f.color]] : [];
  $("e-tipos").replaceChildren(
    ...tipos.map((t) => {
      const li = document.createElement("li");
      li.textContent = t;
      return li;
    }),
  );
  $("e-estado").textContent =
    e === "sinver"
      ? "Aún no la has visto. Estas pistas te ayudarán a encontrarla."
      : e === "vista"
        ? "La has visto, pero aún no la has capturado. Fotografíala bien para leer su entrada."
        : "";

  const pistas = $("e-pistas");
  pistas.replaceChildren();
  dato(pistas, "Familia", f.familia);
  if (e === "capturada") dato(pistas, "Florece en", f.estaciones.map((x) => ESTACION[x]).join(", ") || f.floracion);
  else {
    dato(pistas, "Grupo", f.grupo === "jardin" ? "De jardín" : "Silvestre");
    dato(pistas, "Florece", f.floracion);
  }
  $("e-descripcion").textContent = e === "capturada" ? f.descripcion : "";
  const tox = $("e-toxicidad");
  tox.hidden = !(e === "capturada" && f.toxicidad);
  tox.textContent = e === "capturada" && f.toxicidad ? `¡Cuidado! ${f.toxicidad}` : "";

  const campo = $("e-campo");
  campo.replaceChildren();
  const saber = $("e-saber");
  saber.replaceChildren();
  if (cap) {
    dato(campo, "Dónde vive", f.habitat);
    dato(campo, "Cuándo florece", f.floracion);
    dato(campo, "Curiosidad", f.curiosidad);
    dato(saber, "Otros nombres", f.otros_nombres.length ? enLista(f.otros_nombres) : null);
    dato(saber, "Cuidados", f.cuidados);
    dato(saber, "En el lenguaje de las flores", f.simbolismo);
    dato(saber, "De dónde viene el nombre", f.etimologia);
    dato(saber, "Usos", f.usos);
    if (vistaPixel) {
      const c = document.createElement("canvas");
      c.className = "sprite";
      c.setAttribute("role", "img");
      c.setAttribute("aria-label", `Tu foto de ${f.nombre}, en píxel`);
      pintarFotoPixel(c, cap.foto, 128);
      $("e-foto").replaceChildren(c);
    } else {
      const img = new Image();
      img.src = cap.foto;
      img.alt = `Tu foto de ${f.nombre}`;
      $("e-foto").replaceChildren(img);
    }
    $("e-hallazgo").textContent = `Capturada el ${fechaLarga(cap.fecha)}, ${
      cap.coincidencia === null ? "elegida por ti entre las candidatas" : `con un ${porcentaje(cap.coincidencia)} de coincidencia`
    }.`;
  }
  ir("entrada");
  verPagina("datos", false);
  $("e-nombre").focus({ preventScroll: true });
}

function verPagina(p: Pagina, sonar = true) {
  if (estadoDe(abierta ?? "") !== "capturada") p = "datos";
  if (sonar && p !== pagina) sonidos.cursor();
  pagina = p;
  for (const el of document.querySelectorAll<HTMLElement>("#e-cuerpo .pagina")) el.hidden = el.dataset.pagina !== p;
  for (const b of document.querySelectorAll<HTMLElement>("#paginas button")) b.setAttribute("aria-current", String(b.dataset.pagina === p));
  $("e-cuerpo").scrollTop = 0;
}
for (const b of document.querySelectorAll<HTMLElement>("#paginas button")) b.addEventListener("click", () => verPagina(b.dataset.pagina as Pagina));

function pasarPagina(paso: number) {
  const i = PAGINAS.indexOf(pagina);
  verPagina(PAGINAS[(i + paso + PAGINAS.length) % PAGINAS.length]);
}

function volverALista() {
  sonidos.volver();
  abierta = null;
  ir("lista");
}
$("e-volver").addEventListener("click", volverALista);

$("olvidar").addEventListener("click", async () => {
  const key = abierta;
  const f = key ? flores.get(key) : undefined;
  if (!key || !f) return;
  if (!confirm(`¿Quitar ${f.nombre} del Floridex? Se borrará tu foto y quedará como vista.`)) return;
  await almacen.soltar(key);
  capturas.delete(key);
  // Once captured, it was certainly seen.
  vistas.add(key);
  await almacen.ver({ key, fecha: new Date().toISOString() }).catch(() => {});
  pintarFila(filas.get(key)!, f);
  contar();
  filtrar();
  abrirEntrada(key);
});

// Text box ---------------------------------------------------------------------------
type Opcion = { etiqueta: string; detalle?: string; flor?: Flor; accion: () => void };
type Dialogo = { opciones: Opcion[]; elegida: number; alCerrar?: () => void; escribiendo: boolean; completar: () => void };
let dialogo: Dialogo | null = null;
let temporizadorDialogo = 0;

/** Shows a message typed out letter by letter, optionally with a menu of choices. */
function decir(texto: string, opciones: Opcion[] = [], alCerrar?: () => void) {
  const caja = $("dialogo");
  const p = $("dialogo-texto");
  const menu = $("dialogo-menu");
  caja.hidden = false;
  caja.dataset.menu = String(opciones.length > 0);
  menu.replaceChildren();
  let i = 0;
  // A new message stops the previous one typing.
  clearInterval(temporizadorDialogo);
  const d: Dialogo = { opciones, elegida: 0, alCerrar, escribiendo: true, completar: () => {} };
  const fin = () => {
    clearInterval(temporizadorDialogo);
    p.textContent = texto;
    d.escribiendo = false;
    caja.dataset.escribiendo = "false";
    menu.replaceChildren(
      ...opciones.map((o, n) => {
        const li = document.createElement("li");
        const b = document.createElement("button");
        b.type = "button";
        b.className = "opcion";
        if (o.flor) {
          b.dataset.key = o.flor.key;
          const fig = document.createElement("span");
          fig.className = "figura fila-figura";
          fig.append(figura(o.flor, true));
          b.append(fig);
        }
        const t = document.createElement("span");
        t.className = "opcion-nombre";
        t.textContent = o.etiqueta;
        b.append(t);
        if (o.detalle) {
          const det = document.createElement("span");
          det.className = "opcion-detalle";
          det.textContent = o.detalle;
          b.append(det);
        }
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
          elegir(n);
        });
        li.append(b);
        return li;
      }),
    );
    marcarOpcion();
  };
  d.completar = fin;
  dialogo = d;
  caja.dataset.escribiendo = "true";
  if (lento) fin();
  else {
    p.textContent = "";
    temporizadorDialogo = window.setInterval(() => {
      i++;
      p.textContent = texto.slice(0, i);
      if (i % 3 === 0) sonidos.letra();
      if (i >= texto.length) fin();
    }, 22);
  }
}

function marcarOpcion() {
  const d = dialogo;
  if (!d) return;
  $("dialogo-menu").querySelectorAll(".opcion").forEach((b, n) => b.setAttribute("aria-current", String(n === d.elegida)));
}

function elegir(n: number) {
  const d = dialogo;
  if (!d) return;
  cerrarDialogo(false);
  d.opciones[n]?.accion();
  // A choice that asks nothing more ends the reading («Ninguna», or a capture).
  if (!dialogo && !analizando) terminarLectura();
}

function cerrarDialogo(llamar = true) {
  clearInterval(temporizadorDialogo);
  const d = dialogo;
  dialogo = null;
  $("dialogo").hidden = true;
  if (!llamar) return;
  d?.alCerrar?.();
  if (!dialogo && !analizando) terminarLectura();
}

/** Button A on the text box: finish typing, pick the highlighted choice, or move on. */
function aceptarDialogo() {
  if (!dialogo) return;
  if (dialogo.escribiendo) return dialogo.completar();
  if (dialogo.opciones.length) return elegir(dialogo.elegida);
  sonidos.cursor();
  cerrarDialogo();
}
$("dialogo").addEventListener("click", () => {
  if (!dialogo?.opciones.length || dialogo.escribiendo) aceptarDialogo();
});

// Capturing ------------------------------------------------------------------------
function destello() {
  if (lento) return;
  lcd.classList.remove("destello");
  void lcd.offsetWidth;
  lcd.classList.add("destello");
}

async function capturar(key: string, foto: string, coincidencia: number | null) {
  const f = flores.get(key);
  if (!f) return;
  if (capturas.has(key)) {
    sonidos.abrir();
    decir(`${f.nombre} ya estaba en tu Floridex.`, [], () => abrirEntrada(key, "repetida"));
    return;
  }
  const c: Captura = { key, fecha: new Date().toISOString(), foto, coincidencia };
  try {
    await almacen.capturar(c);
  } catch (err) {
    console.error(err);
    sonidos.error();
    decir("No he podido guardarla: puede que el dispositivo no tenga espacio.", [], terminarLectura);
    return;
  }
  capturas.set(key, c);
  pintarFila(filas.get(key)!, f);
  contar();
  filtrar();
  destello();
  sonidos.captura();
  decir(`¡Capturada! ${f.nombre} se ha registrado en tu Floridex con el número ${numero(f.numero)}.`, [], () => abrirEntrada(key, "nueva"));
}

/** Candidates shown to the user become seen, as in any field guide you leaf through. */
async function marcarVistas(keys: string[]) {
  let nuevas = 0;
  for (const k of keys) {
    if (vistas.has(k) || capturas.has(k)) continue;
    vistas.add(k);
    nuevas++;
    await almacen.ver({ key: k, fecha: new Date().toISOString() }).catch(() => {});
    pintarFila(filas.get(k)!, flores.get(k)!);
  }
  if (nuevas) {
    contar();
    filtrar();
  }
}

// Camera -----------------------------------------------------------------------------
$("ir-camara").addEventListener("click", () => {
  sonidos.abrir();
  ir("camara");
});
$("c-volver").addEventListener("click", volverALista);

/**
 * Each request to turn the camera on gets a number; turning it off or a newer
 * request makes older ones stale, and a stale request that is granted later
 * (a permission prompt still open, two quick «Girar») releases its camera
 * instead of taking over the screen.
 */
let intentoCamara = 0;

function apagarCamara() {
  intentoCamara++;
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  disparar.disabled = true;
  girar.disabled = true;
  marco.dataset.modo = "cerrado";
  visor.velar();
}

async function encender() {
  const texto = $("marco-texto");
  if (!navigator.mediaDevices?.getUserMedia) {
    texto.textContent = "Aquí el navegador no deja usar la cámara en directo (hace falta https). Pulsa «Foto»: en el móvil también abre la cámara.";
    return;
  }
  const mio = ++intentoCamara;
  const vigente = () => mio === intentoCamara && lcd.dataset.pantalla === "camara";
  try {
    stream?.getTracks().forEach((t) => t.stop());
    stream = null;
    const nuevo = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: frontal ? "user" : { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1440 } },
      audio: false,
    });
    if (!vigente()) {
      nuevo.getTracks().forEach((t) => t.stop());
      return;
    }
    stream = nuevo;
    video.srcObject = stream;
    // A camera that never sends a frame must not leave the viewfinder hanging.
    const sinImagen = new Promise<never>((_, ko) => setTimeout(() => ko(new DOMException("sin imagen", "TimeoutError")), 8000));
    await Promise.race([video.play(), sinImagen]);
    if (!video.videoWidth) await Promise.race([new Promise((ok) => video.addEventListener("loadeddata", ok, { once: true })), sinImagen]);
    if (!vigente()) return;
    frontal = stream.getVideoTracks()[0].getSettings().facingMode === "user";
    enVivo();
    const camaras = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === "videoinput");
    girar.disabled = camaras.length < 2;
  } catch (err) {
    // A stale request failing is not news: the newer one decides.
    if (mio !== intentoCamara) return;
    const nombre = (err as DOMException).name;
    texto.textContent =
      nombre === "NotAllowedError"
        ? "No tengo permiso para usar la cámara. Actívalo en los ajustes del navegador o pulsa «Foto»."
        : nombre === "NotFoundError" || nombre === "OverconstrainedError"
          ? "No encuentro ninguna cámara. Pulsa «Foto» para subir una."
          : nombre === "TimeoutError"
            ? "La cámara no envía imagen. Pulsa «Foto»: en el móvil también abre la cámara."
            : "No he podido abrir la cámara. Pulsa «Foto» para subir una.";
    sonidos.error();
    // Release the camera so the phone does not keep it on with no picture.
    stream?.getTracks().forEach((t) => t.stop());
    stream = null;
    disparar.disabled = true;
    girar.disabled = true;
    marco.dataset.modo = "cerrado";
  }
}

/** Back to the live viewfinder after a shot, or to the closed lens. */
function enVivo() {
  if (!stream) {
    marco.dataset.modo = "cerrado";
    visor.velar();
    return;
  }
  visor.enVivo(video, frontal);
  marco.dataset.modo = "vivo";
  disparar.disabled = false;
}

$("abrir-camara").addEventListener("click", encender);
girar.addEventListener("click", () => {
  frontal = !frontal;
  encender();
});

function fotografiar() {
  if (analizando || disparar.disabled || !video.videoWidth) return;
  cerrarDialogo(false);
  terminarLectura();
  sonidos.disparo();
  const c = document.createElement("canvas");
  c.width = video.videoWidth;
  c.height = video.videoHeight;
  const ctx = c.getContext("2d")!;
  if (frontal) {
    ctx.translate(c.width, 0);
    ctx.scale(-1, 1);
  }
  ctx.drawImage(video, 0, 0);
  visor.fijar(c);
  // The frame as pixels, not a JPEG: no compression between camera and model.
  createImageBitmap(c).then((bmp) => analizar(bmp, c));
}
disparar.addEventListener("click", fotografiar);

$<HTMLInputElement>("archivo").addEventListener("change", async (e) => {
  const input = e.target as HTMLInputElement;
  const archivo = input.files?.[0];
  input.value = "";
  if (!archivo || analizando) return;
  // Ends any previous reading and its answer before showing the new photo.
  ir("camara");
  const img = new Image();
  img.src = URL.createObjectURL(archivo);
  try {
    await img.decode();
  } catch {
    sonidos.error();
    decir("Ese archivo no es una imagen que pueda abrir. Prueba con una foto JPG o PNG.");
    return;
  }
  // A copy for the screen and for the photo kept in the Floridex; the model
  // reads the file itself (the worker applies the EXIF rotation).
  const c = document.createElement("canvas");
  const s = Math.min(1, 1600 / Math.max(img.naturalWidth, img.naturalHeight));
  c.width = Math.round(img.naturalWidth * s);
  c.height = Math.round(img.naturalHeight * s);
  c.getContext("2d")!.drawImage(img, 0, 0, c.width, c.height);
  URL.revokeObjectURL(img.src);
  visor.fijar(c);
  analizar(archivo, c);
});

/** One analysis at a time: «Foto» and the shutter wait for the answer. */
let analizando = false;
function ocupado(si: boolean) {
  analizando = si;
  $<HTMLInputElement>("archivo").disabled = si;
  document.querySelector('label[for="archivo"]')!.setAttribute("aria-disabled", String(si));
}

/**
 * The reading in progress or showing its answer. Its end is one transition,
 * terminarLectura, that every way out goes through (a new photo, entering or
 * leaving the camera screen, any dismissal of the text box): the still photo
 * goes, the viewfinder is live again (or the closed lens) and the shutter is
 * ready. A result that arrives after its reading ended is ignored.
 */
let lecturaId = 0;

function terminarLectura() {
  lecturaId++;
  ocupado(false);
  if (estado.textContent?.startsWith("Analizando")) estado.textContent = "";
  enVivo();
}

async function analizar(captura: Blob | ImageBitmap, lienzo: HTMLCanvasElement) {
  const mia = lecturaId;
  ocupado(true);
  marco.dataset.modo = "fijo";
  disparar.disabled = true;
  estado.textContent = modeloListo ? "Analizando…" : "Analizando… (el Floridex aún se está cargando)";
  try {
    const [candidatos] = await Promise.all([clasificar(captura), visor.revelar(1400), listo]);
    // The user left this reading meanwhile: its answer no longer belongs anywhere.
    if (mia !== lecturaId) return;
    estado.textContent = "";
    const foto = reducir(lienzo, 480, 0.82);
    const mejor = candidatos[0];
    const sinFlor = mejor.label === NO_FLOR;
    if (!sinFlor && mejor.score >= UMBRAL_DUDA && flores.has(mejor.label)) {
      await capturar(mejor.label, foto, mejor.score);
      return;
    }
    // Unsure or no flower: the user picks among the candidates, or tries again.
    const umbral = sinFlor ? 0.05 : 0.01;
    const opciones = candidatos.filter((c) => c.label !== NO_FLOR && c.score >= umbral && flores.has(c.label)).slice(0, MAX_CANDIDATAS);
    await marcarVistas(opciones.map((c) => c.label));
    sonidos.vista();
    const menu: Opcion[] = opciones.map((c) => {
      const f = flores.get(c.label)!;
      return { etiqueta: f.nombre, detalle: porcentaje(c.score), flor: f, accion: () => capturar(f.key, foto, null) };
    });
    if (menu.length) menu.push({ etiqueta: "Ninguna", accion: terminarLectura });
    decir(
      sinFlor
        ? menu.length
          ? "No veo ninguna flor clara. Si la hay, ¿es alguna de estas?"
          : "No veo ninguna flor. Acércate a una sola flor, con buena luz, y que ocupe el centro."
        : "No estoy seguro. ¿Cuál de estas es?",
      menu,
      terminarLectura,
    );
  } catch (err) {
    if (mia !== lecturaId) return;
    console.error(err);
    sonidos.error();
    terminarLectura();
    estado.textContent = "No he podido analizar la foto. Inténtalo otra vez.";
  } finally {
    // The answer is on screen: the photo may stay until it is dismissed, but «Foto» is free again.
    if (mia === lecturaId) ocupado(false);
  }
}

function reducir(lienzo: HTMLCanvasElement, lado: number, calidad: number): string {
  const s = Math.min(1, lado / Math.max(lienzo.width, lienzo.height));
  const c = document.createElement("canvas");
  c.width = Math.round(lienzo.width * s);
  c.height = Math.round(lienzo.height * s);
  c.getContext("2d")!.drawImage(lienzo, 0, 0, c.width, c.height);
  return c.toDataURL("image/jpeg", calidad);
}

// Buttons and keys ---------------------------------------------------------------------
function tecla(k: string): boolean {
  const d = dialogo;
  if (d) {
    if (k === "Enter" || k === " ") aceptarDialogo();
    else if (k === "ArrowUp" || k === "ArrowDown") {
      if (d.opciones.length && !d.escribiendo) {
        d.elegida = (d.elegida + (k === "ArrowUp" ? -1 : 1) + d.opciones.length) % d.opciones.length;
        marcarOpcion();
        sonidos.cursor();
      }
    } else if (k === "Escape") {
      if (d.escribiendo) d.completar();
      else if (d.opciones.length) elegir(d.opciones.length - 1);
      else cerrarDialogo();
    } else return false;
    return true;
  }
  const p = lcd.dataset.pantalla as Pantalla;
  if (p === "lista") {
    if (k === "ArrowUp") moverCursor(-1);
    else if (k === "ArrowDown") moverCursor(1);
    else if (k === "ArrowLeft") moverCursor(-10);
    else if (k === "ArrowRight") moverCursor(10);
    else if (k === "Enter" && visibles[cursor]) abrirEntrada(visibles[cursor].key);
    else return false;
  } else if (p === "entrada") {
    if (k === "ArrowLeft") pasarPagina(-1);
    else if (k === "ArrowRight" || k === "Enter") pasarPagina(1);
    else if (k === "ArrowUp") $("e-cuerpo").scrollBy({ top: -80 });
    else if (k === "ArrowDown") $("e-cuerpo").scrollBy({ top: 80 });
    else if (k === "Escape") volverALista();
    else return false;
  } else if (p === "camara") {
    if (k === "Enter") stream ? fotografiar() : encender();
    else if (k === "Escape") volverALista();
    else return false;
  }
  return true;
}

document.addEventListener("keydown", (e) => {
  const t = e.target as HTMLElement;
  if (t.matches("input, select, textarea")) {
    // From the search box, Down hands control back to the list.
    if (t === buscador && e.key === "ArrowDown") {
      buscador.blur();
      tecla("ArrowDown");
      e.preventDefault();
    }
    return;
  }
  // Buttons keep their own Enter and Space, except on the text box and the
  // device's own pad and buttons, which stand for A.
  if ((e.key === "Enter" || e.key === " ") && t.matches("button:not([data-tecla])") && !dialogo) return;
  if (tecla(e.key)) e.preventDefault();
});

for (const b of document.querySelectorAll<HTMLElement>("[data-tecla]")) {
  b.addEventListener("click", () => tecla(b.dataset.tecla!));
}

const vista = $("vista");
function pintarVista() {
  vista.setAttribute("aria-pressed", String(vistaPixel));
  // "Vista" is dropped on narrow screens (CSS) so the shell's header fits.
  vista.innerHTML = `<span class="vista-largo">Vista</span>${vistaPixel ? "píxel" : "normal"}`;
  vista.setAttribute("aria-label", vistaPixel ? "Vista píxel" : "Vista normal");
}
pintarVista();
vista.addEventListener("click", () => {
  vistaPixel = !vistaPixel;
  visor.pixel = vistaPixel;
  try {
    localStorage.setItem(CLAVE_VISTA, vistaPixel ? "pixel" : "normal");
  } catch {
    // Private mode: the choice lasts for this visit only.
  }
  pintarVista();
  sonidos.cursor();
  // Repaint every captured flower, the preview and the open entry, on the same page.
  for (const k of capturas.keys()) pintarFila(filas.get(k)!, flores.get(k)!);
  moverCursor(0, false);
  if (abierta && lcd.dataset.pantalla === "entrada") {
    const pg = pagina;
    abrirEntrada(abierta, selloAbierto);
    verPagina(pg, false);
  }
});

const sonido = $("sonido");
sonido.setAttribute("aria-pressed", String(sonidoActivo()));
sonido.addEventListener("click", () => {
  const ahora = alternarSonido();
  sonido.setAttribute("aria-pressed", String(ahora));
  sonidos.cursor();
});

ir("lista");
