// Pixel art: the silhouette of a flower not captured yet, and the pixel view
// of captured ones (their sprite, and photos printed in the four greens of a
// 90s pocket camera). In the normal view photos are shown as they are.

export const VERDES = ["#0f380f", "#306230", "#8bac0f", "#9bbc0f"];
const LADO = 48;
const RGB = VERDES.map((h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)));
// 4 x 4 ordered dither, so mid tones read as texture instead of flat bands.
const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];

// Generic flower for entries without a sprite yet, 16 x 16, drawn at 3x.
// 0 transparent, 1 to 4 the greens from dark to light.
const FLOR = [
  "0000001111000000",
  "0000014444100000",
  "0001114444111000",
  "0014441441444100",
  "0144444114444410",
  "0144441221444410",
  "0014412332144100",
  "0001123333211000",
  "0014412332144100",
  "0144441221444410",
  "0144444114444410",
  "0014441111444100",
  "0001110220111000",
  "0000001221000000",
  "0000012221000000",
  "0000001110000000",
];

const imagenes = new Map<string, Promise<HTMLImageElement | null>>();

function cargar(src: string): Promise<HTMLImageElement | null> {
  let p = imagenes.get(src);
  if (!p) {
    p = new Promise((ok) => {
      const img = new Image();
      img.onload = () => ok(img);
      img.onerror = () => ok(null);
      img.src = src;
    });
    imagenes.set(src, p);
  }
  return p;
}

function florGenerica(g: CanvasRenderingContext2D) {
  for (let y = 0; y < 16; y++)
    for (let x = 0; x < 16; x++) {
      const v = Number(FLOR[y][x]);
      if (!v) continue;
      g.fillStyle = VERDES[v - 1];
      g.fillRect(x * 3, y * 3, 3, 3);
    }
}

/**
 * Draws a flower's silhouette on a 48 x 48 canvas: the shape is there, but
 * not who it is. Without a sprite, a generic pixel flower.
 */
export async function pintarSilueta(c: HTMLCanvasElement, src: string | null) {
  c.width = c.height = LADO;
  const g = c.getContext("2d")!;
  const marca = `${src}|silueta`;
  c.dataset.sprite = marca;
  const img = src ? await cargar(src) : null;
  // A newer call may have taken over this canvas while the image loaded.
  if (c.dataset.sprite !== marca) return;
  g.clearRect(0, 0, LADO, LADO);
  if (img) g.drawImage(img, 0, 0, LADO, LADO);
  else florGenerica(g);
  g.globalCompositeOperation = "source-in";
  g.fillStyle = VERDES[1];
  g.fillRect(0, 0, LADO, LADO);
  g.globalCompositeOperation = "source-over";
}

/** Draws a flower's sprite as it is: pixel art in the four greens. */
export async function pintarSprite(c: HTMLCanvasElement, src: string) {
  c.width = c.height = LADO;
  const marca = `${src}|color`;
  c.dataset.sprite = marca;
  const img = await cargar(src);
  if (c.dataset.sprite !== marca || !img) return;
  const g = c.getContext("2d")!;
  g.clearRect(0, 0, LADO, LADO);
  g.drawImage(img, 0, 0, LADO, LADO);
}

/**
 * Prints a photo the way a pocket camera of the 90s would: low resolution,
 * four greens, ordered dithering. `cuadrada` crops the centre square.
 */
export async function pintarFotoPixel(c: HTMLCanvasElement, src: string, ancho: number, cuadrada = false) {
  const marca = `${src.slice(0, 64)}|${ancho}|pixel`;
  c.dataset.sprite = marca;
  const img = await cargar(src);
  if (c.dataset.sprite !== marca || !img) return;
  const lado = Math.min(img.naturalWidth, img.naturalHeight);
  const [sx, sy, sw, sh] = cuadrada
    ? [(img.naturalWidth - lado) / 2, (img.naturalHeight - lado) / 2, lado, lado]
    : [0, 0, img.naturalWidth, img.naturalHeight];
  const alto = Math.round((ancho * sh) / sw);
  c.width = ancho;
  c.height = alto;
  const g = c.getContext("2d", { willReadFrequently: true })!;
  g.drawImage(img, sx, sy, sw, sh, 0, 0, ancho, alto);
  const d = g.getImageData(0, 0, ancho, alto);
  const lum = new Float32Array(ancho * alto);
  let min = 255,
    max = 0;
  for (let i = 0; i < lum.length; i++) {
    const l = 0.299 * d.data[i * 4] + 0.587 * d.data[i * 4 + 1] + 0.114 * d.data[i * 4 + 2];
    lum[i] = l;
    min = Math.min(min, l);
    max = Math.max(max, l);
  }
  const rango = Math.max(1, max - min);
  for (let y = 0; y < alto; y++)
    for (let x = 0; x < ancho; x++) {
      const i = y * ancho + x;
      const umbral = (BAYER[(y % 4) * 4 + (x % 4)] + 0.5) / 16 - 0.5;
      const n = Math.min(3, Math.max(0, Math.round(((lum[i] - min) / rango) * 3 + umbral)));
      d.data.set([...RGB[n], 255], i * 4);
    }
  g.putImageData(d, 0, 0);
}
