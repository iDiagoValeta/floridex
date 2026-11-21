// WebGL viewfinder: the camera or the photo cropped to fill the screen,
// either as they are (normal view) or as a 90s pocket camera would show them
// (pixel view: 128 big pixels across, four greens, ordered dithering). While
// the model reads a captured frame, a scan line sweeps down over it. The
// model always gets the full-colour photo. WebGL instead of
// a plain <video> because iOS Safari is most reliable drawing the camera
// this way, and it lets a still frame and the live feed share one surface.

const VERT = `
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`;

const FRAG = `
precision mediump float;
uniform sampler2D u_tex;
uniform vec2 u_escala;
uniform vec2 u_res;
uniform float u_espejo;
uniform float u_revelado;
uniform float u_pixel;
varying vec2 v_uv;

// 4 x 4 ordered dither from two nested 2 x 2 patterns, in [0, 1).
float bayer2(vec2 a) {
  a = floor(a);
  return fract(a.x / 2.0 + a.y * a.y * 0.75);
}
float bayer4(vec2 a) {
  return bayer2(0.5 * a) * 0.25 + bayer2(a);
}

void main() {
  float tam = u_res.x / 128.0;
  vec2 celda = floor(gl_FragCoord.xy / tam);
  vec2 uv = u_pixel > 0.5 ? (celda + 0.5) * tam / u_res : v_uv;
  uv.y = 1.0 - uv.y;
  if (u_espejo > 0.5) uv.x = 1.0 - uv.x;
  uv = (uv - 0.5) * u_escala + 0.5;
  vec3 c = texture2D(u_tex, uv).rgb;
  if (u_pixel > 0.5) {
    float l = smoothstep(0.08, 0.92, dot(c, vec3(0.299, 0.587, 0.114)));
    float n = clamp(floor(l * 3.0 + bayer4(celda)), 0.0, 3.0);
    c = n < 0.5 ? vec3(0.059, 0.220, 0.059) : n < 1.5 ? vec3(0.188, 0.384, 0.188) : n < 2.5 ? vec3(0.545, 0.675, 0.059) : vec3(0.608, 0.737, 0.059);
  }
  // Scan line: 3 px of the screen's lightest green, only while reading.
  float y = 1.0 - v_uv.y;
  float linea = step(abs(y - u_revelado), 1.5 / u_res.y) * step(u_revelado, 1.0);
  gl_FragColor = vec4(mix(c, vec3(0.608, 0.737, 0.059), linea), 1.0);
}`;

export type Fuente = HTMLVideoElement | HTMLImageElement | HTMLCanvasElement | ImageBitmap;

/** What the app needs from a viewfinder. */
export interface VisorComun {
  pixel: boolean;
  enVivo(video: HTMLVideoElement, espejo: boolean): void;
  fijar(img: Fuente, espejo?: boolean): void;
  revelar(ms?: number): Promise<void>;
  velar(): void;
}

function medidas(f: Fuente): [number, number] {
  if (f instanceof HTMLVideoElement) return [f.videoWidth, f.videoHeight];
  if (f instanceof HTMLImageElement) return [f.naturalWidth, f.naturalHeight];
  return [f.width, f.height];
}

/**
 * Fallback without WebGL: the picture as it is on a 2D canvas, cropped to fill
 * the screen. No pixel view of the camera and no scan line.
 */
export class VisorPlano implements VisorComun {
  pixel = false;
  private fuente: Fuente | null = null;
  private espejo = false;

  constructor(private lienzo: HTMLCanvasElement) {
    requestAnimationFrame(this.pintar);
  }

  enVivo(video: HTMLVideoElement, espejo: boolean) {
    this.fuente = video;
    this.espejo = espejo;
  }

  fijar(img: Fuente, espejo = false) {
    this.fuente = img;
    this.espejo = espejo;
  }

  revelar(ms = 1600): Promise<void> {
    return new Promise((ok) => setTimeout(ok, matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : ms));
  }

  velar() {
    this.fuente = null;
  }

  private pintar = () => {
    requestAnimationFrame(this.pintar);
    const f = this.fuente;
    if (!f) return;
    const [w, h] = medidas(f);
    if (!w || !h) return;
    const lz = this.lienzo;
    const dpr = Math.min(devicePixelRatio || 1, 2);
    const cw = Math.round(lz.clientWidth * dpr), ch = Math.round(lz.clientHeight * dpr);
    if (lz.width !== cw || lz.height !== ch) {
      lz.width = cw;
      lz.height = ch;
    }
    const g = lz.getContext("2d");
    if (!g) return;
    const s = Math.max(cw / w, ch / h);
    g.save();
    if (this.espejo) {
      g.translate(cw, 0);
      g.scale(-1, 1);
    }
    g.drawImage(f, (cw - w * s) / 2, (ch - h * s) / 2, w * s, h * s);
    g.restore();
  };
}

export class Visor implements VisorComun {
  private gl: WebGLRenderingContext;
  private tex: WebGLTexture;
  private u: Record<string, WebGLUniformLocation | null> = {};
  private fuente: Fuente | null = null;
  private vivo = false;
  private espejo = false;
  private revelado = 0;
  private animacion = 0;
  /** Pixel view: the pocket-camera look instead of the picture as it is. */
  pixel = false;

  constructor(private lienzo: HTMLCanvasElement) {
    const gl = lienzo.getContext("webgl", { preserveDrawingBuffer: true, antialias: false });
    if (!gl) throw new Error("WebGL no disponible");
    this.gl = gl;
    const prog = gl.createProgram()!;
    for (const [tipo, src] of [[gl.VERTEX_SHADER, VERT], [gl.FRAGMENT_SHADER, FRAG]] as const) {
      const s = gl.createShader(tipo)!;
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? "shader");
      gl.attachShader(prog, s);
    }
    gl.linkProgram(prog);
    gl.useProgram(prog);
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(prog, "a_pos");
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    for (const n of ["u_tex", "u_escala", "u_res", "u_espejo", "u_revelado", "u_pixel"]) this.u[n] = gl.getUniformLocation(prog, n);
    this.tex = gl.createTexture()!;
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    requestAnimationFrame(this.pintar);
  }

  /** Live source (camera): re-uploaded every frame. */
  enVivo(video: HTMLVideoElement, espejo: boolean) {
    this.animacion++;
    this.fuente = video;
    this.vivo = true;
    this.espejo = espejo;
    this.revelado = 2;
  }

  /** Still source (captured frame or uploaded photo): uploaded once. */
  fijar(img: Fuente, espejo = false) {
    this.animacion++;
    this.fuente = img;
    this.vivo = false;
    this.espejo = espejo;
    this.revelado = 0;
    this.subir();
  }

  /** Animate the development from thread to colour; resolves when finished. */
  revelar(ms = 1600): Promise<void> {
    const inicio = performance.now();
    // A new picture (or the live camera) stops this animation.
    const mia = ++this.animacion;
    return new Promise((ok) => {
      const paso = () => {
        if (mia !== this.animacion) return ok();
        const t = Math.min(1, (performance.now() - inicio) / ms);
        this.revelado = 1.1 * (1 - Math.pow(1 - t, 2));
        if (t < 1) requestAnimationFrame(paso);
        else ok();
      };
      if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
        this.revelado = 2;
        ok();
      } else paso();
    });
  }

  velar() {
    this.animacion++;
    this.revelado = 0;
  }

  private dims(f: Fuente): [number, number] {
    if (f instanceof HTMLVideoElement) return [f.videoWidth, f.videoHeight];
    if (f instanceof HTMLImageElement) return [f.naturalWidth, f.naturalHeight];
    return [f.width, f.height];
  }

  private subir() {
    const f = this.fuente;
    if (!f) return;
    const [w, h] = this.dims(f);
    if (!w || !h) return;
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, f);
  }

  private pintar = () => {
    requestAnimationFrame(this.pintar);
    const f = this.fuente;
    if (!f) return;
    const [w, h] = this.dims(f);
    if (!w || !h) return;
    const lz = this.lienzo;
    const dpr = Math.min(devicePixelRatio || 1, 2);
    const cw = Math.round(lz.clientWidth * dpr), ch = Math.round(lz.clientHeight * dpr);
    if (lz.width !== cw || lz.height !== ch) {
      lz.width = cw;
      lz.height = ch;
    }
    const gl = this.gl;
    gl.viewport(0, 0, cw, ch);
    if (this.vivo) this.subir();
    // "cover" crop: scale UVs so the source fills the frame without distortion.
    const ar = (w / h) / (cw / ch);
    const escala = ar > 1 ? [1 / ar, 1] : [1, ar];
    gl.uniform2f(this.u.u_escala, escala[0], escala[1]);
    gl.uniform1f(this.u.u_espejo, this.espejo ? 1 : 0);
    gl.uniform1f(this.u.u_revelado, this.revelado);
    gl.uniform2f(this.u.u_res, cw, ch);
    gl.uniform1f(this.u.u_pixel, this.pixel ? 1 : 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  };
}
