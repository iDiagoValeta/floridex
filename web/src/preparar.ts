// The model's preprocessing, done exactly as in training (torchvision on PIL):
// shortest side to 256 with PIL's bicubic filter, centre crop 224 with
// Python's rounding, ImageNet mean and std. transformers.js does it with the
// browser canvas instead (bilinear, and a crop at half-pixel offsets that
// blurs the image), which changed answers on small photos: a 179 x 240 daisy
// went from "unsure" in Python to a confident wrong answer in the browser.

export type Preprocesado = {
  lado: number; // shortest side after resizing
  recorte: number; // centre crop, square
  media: [number, number, number];
  desv: [number, number, number];
};

const PRECISION = 22; // PIL's fixed-point bits for 8-bit images
const ESCALA = 2 ** PRECISION;

function bicubica(x: number): number {
  const a = -0.5;
  x = Math.abs(x);
  if (x < 1) return ((a + 2) * x - (a + 3)) * x * x + 1;
  if (x < 2) return (((x - 5) * x + 8) * x - 4) * a;
  return 0;
}

/** PIL's precompute_coeffs (ImagingResample, bicubic) in fixed point. */
function coeficientes(entrada: number, salida: number) {
  const escala = entrada / salida;
  const filtro = Math.max(escala, 1);
  const soporte = 2 * filtro;
  const tam = Math.ceil(soporte) * 2 + 1;
  const limites = new Int32Array(salida * 2);
  const pesos = new Float64Array(salida * tam);
  for (let xx = 0; xx < salida; xx++) {
    const centro = (xx + 0.5) * escala;
    const xmin = Math.max(Math.trunc(centro - soporte + 0.5), 0);
    const n = Math.min(Math.trunc(centro + soporte + 0.5), entrada) - xmin;
    let total = 0;
    const k = new Float64Array(n);
    for (let x = 0; x < n; x++) {
      k[x] = bicubica((x + xmin - centro + 0.5) / filtro);
      total += k[x];
    }
    for (let x = 0; x < n; x++) {
      const v = total !== 0 ? k[x] / total : 0;
      pesos[xx * tam + x] = v < 0 ? Math.trunc(-0.5 + v * ESCALA) : Math.trunc(0.5 + v * ESCALA);
    }
    limites[xx * 2] = xmin;
    limites[xx * 2 + 1] = n;
  }
  return { limites, pesos, tam };
}

function recortar8(v: number): number {
  if (v <= 0) return 0;
  const r = Math.floor(v / ESCALA);
  return r > 255 ? 255 : r;
}

/**
 * Resizes an RGB image (3 bytes per pixel) like PIL's Image.resize(BICUBIC):
 * a horizontal pass, rounded to 8 bits, then a vertical one.
 */
export function redimensionar(rgb: Uint8ClampedArray, w: number, h: number, nw: number, nh: number): Uint8ClampedArray {
  let datos = rgb;
  let ancho = w;
  if (nw !== w) {
    const { limites, pesos, tam } = coeficientes(w, nw);
    const fuera = new Uint8ClampedArray(nw * h * 3);
    for (let y = 0; y < h; y++)
      for (let xx = 0; xx < nw; xx++) {
        const xmin = limites[xx * 2], n = limites[xx * 2 + 1];
        for (let c = 0; c < 3; c++) {
          let s = ESCALA / 2;
          for (let x = 0; x < n; x++) s += datos[(y * ancho + xmin + x) * 3 + c] * pesos[xx * tam + x];
          fuera[(y * nw + xx) * 3 + c] = recortar8(s);
        }
      }
    datos = fuera;
    ancho = nw;
  }
  if (nh !== h) {
    const { limites, pesos, tam } = coeficientes(h, nh);
    const fuera = new Uint8ClampedArray(ancho * nh * 3);
    for (let yy = 0; yy < nh; yy++) {
      const ymin = limites[yy * 2], n = limites[yy * 2 + 1];
      for (let x = 0; x < ancho; x++)
        for (let c = 0; c < 3; c++) {
          let s = ESCALA / 2;
          for (let y = 0; y < n; y++) s += datos[((ymin + y) * ancho + x) * 3 + c] * pesos[yy * tam + y];
          fuera[(yy * ancho + x) * 3 + c] = recortar8(s);
        }
    }
    datos = fuera;
  }
  return datos;
}

/** Python's round(): halves go to the even neighbour. */
function redondear(x: number): number {
  const r = Math.round(x);
  return Math.abs(x % 1) === 0.5 && r % 2 !== 0 ? r - 1 : r;
}

/**
 * RGBA pixels (as from a canvas) to the model's input: [1, 3, crop, crop]
 * float32, channels first.
 */
export function preparar(rgba: Uint8ClampedArray, w: number, h: number, p: Preprocesado): Float32Array {
  const rgb = new Uint8ClampedArray(w * h * 3);
  for (let i = 0; i < w * h; i++) {
    rgb[i * 3] = rgba[i * 4];
    rgb[i * 3 + 1] = rgba[i * 4 + 1];
    rgb[i * 3 + 2] = rgba[i * 4 + 2];
  }
  // torchvision's Resize(int): the long side is truncated, not rounded.
  const [nw, nh] = w <= h ? [p.lado, Math.trunc((p.lado * h) / w)] : [Math.trunc((p.lado * w) / h), p.lado];
  const r = redimensionar(rgb, w, h, nw, nh);
  const x0 = redondear((nw - p.recorte) / 2);
  const y0 = redondear((nh - p.recorte) / 2);
  const n = p.recorte;
  const salida = new Float32Array(3 * n * n);
  for (let y = 0; y < n; y++)
    for (let x = 0; x < n; x++)
      for (let c = 0; c < 3; c++) {
        const v = r[((y0 + y) * nw + x0 + x) * 3 + c] / 255;
        salida[c * n * n + y * n + x] = (v - p.media[c]) / p.desv[c];
      }
  return salida;
}
