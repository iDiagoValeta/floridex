// The user's Floridex, in IndexedDB on this device only: flowers seen (the
// model offered them as candidates) and flowers captured (identified, with
// the user's photo). IndexedDB rather than localStorage: a photo per flower
// for 500 flowers is well past localStorage's 5 MB.

export type Captura = {
  key: string;
  /** ISO date of the first capture. */
  fecha: string;
  /** The user's photo, a small JPEG data URL (Safari stores strings more reliably than Blobs). */
  foto: string;
  /** The model's confidence, or null when the user picked it among the candidates. */
  coincidencia: number | null;
};
export type Vista = { key: string; fecha: string };

const BD = "floridex";
const CAPTURAS = "hallazgos";
const VISTAS = "vistas";

function abrir(): Promise<IDBDatabase> {
  return new Promise((ok, ko) => {
    const r = indexedDB.open(BD, 2);
    r.onupgradeneeded = () => {
      const bd = r.result;
      if (!bd.objectStoreNames.contains(CAPTURAS)) bd.createObjectStore(CAPTURAS, { keyPath: "key" });
      if (!bd.objectStoreNames.contains(VISTAS)) bd.createObjectStore(VISTAS, { keyPath: "key" });
    };
    r.onsuccess = () => {
      const d = r.result;
      // Another tab upgrading the database, or the browser closing it: let go
      // and open it again on the next use.
      d.onversionchange = () => {
        d.close();
        bd = null;
      };
      d.onclose = () => {
        bd = null;
      };
      ok(d);
    };
    r.onerror = () => ko(r.error);
    // An older tab still holds the database open: it closes on versionchange.
    r.onblocked = () => console.warn("Floridex: la base de datos espera a que se cierre otra pestaña");
  });
}

let bd: Promise<IDBDatabase> | null = null;

async function tabla(nombre: string, modo: IDBTransactionMode): Promise<IDBObjectStore> {
  bd ??= abrir();
  try {
    return (await bd).transaction(nombre, modo).objectStore(nombre);
  } catch (err) {
    // A connection closed under us (InvalidStateError): one fresh try.
    bd = abrir();
    return (await bd).transaction(nombre, modo).objectStore(nombre);
  }
}

function pedir<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((ok, ko) => {
    r.onsuccess = () => ok(r.result);
    r.onerror = () => ko(r.error);
  });
}

export async function capturas(): Promise<Captura[]> {
  return pedir((await tabla(CAPTURAS, "readonly")).getAll() as IDBRequest<Captura[]>);
}

export async function vistas(): Promise<Vista[]> {
  return pedir((await tabla(VISTAS, "readonly")).getAll() as IDBRequest<Vista[]>);
}

export async function capturar(c: Captura): Promise<void> {
  await pedir((await tabla(CAPTURAS, "readwrite")).put(c));
  // Ask the browser not to evict the collection under storage pressure (Safari does).
  navigator.storage?.persist?.().catch(() => {});
}

export async function ver(v: Vista): Promise<void> {
  await pedir((await tabla(VISTAS, "readwrite")).put(v));
}

/** Forgets a capture; the flower stays seen. */
export async function soltar(key: string): Promise<void> {
  await pedir((await tabla(CAPTURAS, "readwrite")).delete(key));
}
