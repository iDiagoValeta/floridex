// 8-bit beeps made on the fly with Web Audio square waves: no sound files.
// iOS only lets audio start inside a user gesture, so the context is created
// on the first press.

const CLAVE = "floridex:sonido";
let ctx: AudioContext | null = null;

export function activo(): boolean {
  try {
    return localStorage.getItem(CLAVE) !== "no";
  } catch {
    return true;
  }
}

export function alternar(): boolean {
  const nuevo = !activo();
  try {
    localStorage.setItem(CLAVE, nuevo ? "si" : "no");
  } catch {
    // Private mode: the choice lasts for this visit only.
  }
  return nuevo;
}

/** Notes as [frequency in Hz, duration in ms]; 0 Hz is a rest. */
function tocar(notas: Array<[number, number]>, volumen = 0.05) {
  if (!activo()) return;
  try {
    ctx ??= new AudioContext();
    if (ctx.state === "suspended") ctx.resume();
    let t = ctx.currentTime;
    for (const [f, ms] of notas) {
      const d = ms / 1000;
      if (f > 0) {
        const o = ctx.createOscillator();
        const g = ctx.createGain();
        o.type = "square";
        o.frequency.value = f;
        g.gain.setValueAtTime(volumen, t);
        g.gain.exponentialRampToValueAtTime(0.0001, t + d);
        o.connect(g).connect(ctx.destination);
        o.start(t);
        o.stop(t + d);
      }
      t += d;
    }
  } catch {
    // No Web Audio: the app works the same in silence.
  }
}

export const sonidos = {
  cursor: () => tocar([[1320, 28]], 0.03),
  abrir: () => tocar([[784, 45], [1175, 70]]),
  volver: () => tocar([[880, 40], [587, 60]]),
  letra: () => tocar([[1760, 12]], 0.015),
  vista: () => tocar([[523, 70], [0, 20], [659, 110]]),
  captura: () => tocar([[523, 80], [659, 80], [784, 80], [1047, 80], [0, 40], [784, 80], [1047, 260]], 0.06),
  error: () => tocar([[196, 110], [147, 180]]),
  disparo: () => tocar([[2093, 20], [1047, 40]], 0.04),
};
