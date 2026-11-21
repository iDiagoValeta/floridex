"""Builds the Floridex: one numbered entry per flower the model knows.

    python3 shared/floridex.py

Merges the first 182 cards (`flores.json`) with the newer ones
(`fichas2/nuevas_unidas.json`), checks that there is exactly one card per
model label (the "no flower" reject class aside) and writes
`floridex.json`, the only copy: both apps read it from here at build time
(web/scripts/copy-assets.mjs, the Android Gradle task copiarDatos). Run it
again after `sprites.py`, after new reference photos or after a new model.

Each entry gains:
- numero: its number in the index, frozen in `numeracion.json` so it never
  changes. The first 516 were given by family and then scientific name, so
  relatives sit together; flowers the model learns later take the next free
  numbers (in the same order among themselves), and the number of a flower
  that leaves the model is never reused.
- estaciones: the seasons it blooms in (northern hemisphere), read from the
  free-text `floracion`, for the season filter.
- color: the colour family of the petal colour, for the colour filter.
- foto: path of its reference photo (`miniaturas2/`), shown as is once the
  flower is captured; null when there is none (the app then shows the user's
  own photo).
- credito: who made that photo and under which licence, from
  `miniaturas2/creditos.json` ({"autor", "licencia", "fuente"}); the apps show
  it with the photo and with the sprite drawn from it. A photo without a
  credit is not used, nor its sprite: the entry falls back to the generic
  pixel flower.
- sprite: path of its silhouette source (made by `sprites.py` from that
  photo), or null when there is none yet (the apps then draw a generic
  pixel flower).
"""

import colorsys
import json
import re
from pathlib import Path

AQUI = Path(__file__).resolve().parent
# Reference photos, one per flower (240 px square webp), made by ml/miniaturas.py.
FOTOS = "miniaturas2"
NO_FLOR = "no flower"
# This class was merged into "liverleaf" in the new taxonomy.
DESCARTADAS = {"Anemone hepatica"}
ESTACIONES = ["primavera", "verano", "otoño", "invierno"]
MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
         "septiembre", "octubre", "noviembre", "diciembre"]
# Month index (0 = January) to season.
MES_ESTACION = ["invierno", "invierno", "primavera", "primavera", "primavera", "verano",
                "verano", "verano", "otoño", "otoño", "otoño", "invierno"]
TODO_EL_AÑO = ("todo el año", "gran parte del año", "distintas épocas")


def slug(k):
    return re.sub(r"[^a-z0-9]+", "-", k.lower()).strip("-")


def tramos(texto, palabras):
    """Indices of `palabras` named in `texto`, expanding "de X a Y" ranges."""
    hallados = sorted((m.start(), m.end(), palabras.index(m.group(1)))
                      for m in re.finditer(r"\b(" + "|".join(palabras) + r")\b", texto))
    fuera = set()
    for i, (_, fin, idx) in enumerate(hallados):
        fuera.add(idx)
        if i + 1 < len(hallados):
            entre = texto[fin:hallados[i + 1][0]]
            # "de mayo a julio", "de finales de primavera a principios de verano"
            if re.search(r"\bal?\b", entre) and not re.search(r"[,;]|\by\b", entre):
                j, fin_idx = idx, hallados[i + 1][2]
                while j != fin_idx:
                    j = (j + 1) % len(palabras)
                    fuera.add(j)
    return fuera


def estaciones(floracion):
    t = floracion.lower()
    if any(x in t for x in TODO_EL_AÑO):
        return list(ESTACIONES)
    meses = tramos(t, MESES)
    if meses:
        vistas = {MES_ESTACION[m] for m in meses}
    else:
        vistas = {ESTACIONES[i] for i in tramos(t, ESTACIONES)}
        if "heladas" in t:
            vistas.add("otoño")
    return [e for e in ESTACIONES if e in vistas]


def familia_color(hexa):
    r, g, b = (int(hexa[i:i + 2], 16) / 255 for i in (1, 3, 5))
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    h *= 360
    if l > 0.85 or (s < 0.2 and l > 0.6):
        return "blanco"
    if s < 0.2:
        return "verde" if 70 <= h < 170 else "blanco"
    if h < 12 or h >= 345:
        return "rosa" if l > 0.65 else "rojo"
    if h < 40:
        return "naranja"
    if h < 68:
        return "amarillo"
    if h < 170:
        return "verde"
    if h < 255:
        return "azul"
    if h < 290:
        return "morado"
    return "rosa"


def main():
    # The labels of the model both apps ship (modelo/config.json, id2label).
    id2label = json.loads((AQUI / "modelo/config.json").read_text())["id2label"]
    etiquetas = [id2label[str(i)] for i in range(len(id2label))]
    fichas = json.loads((AQUI / "flores.json").read_text()) + json.loads((AQUI / "fichas2/nuevas_unidas.json").read_text())
    fichas = [f for f in fichas if f["key"] not in DESCARTADAS]

    claves = [f["key"] for f in fichas]
    flores = [e for e in etiquetas if e != NO_FLOR]
    assert len(claves) == len(set(claves)), "duplicate card keys"
    assert sorted(claves) == sorted(flores), (
        f"cards without label: {sorted(set(claves) - set(flores))}; labels without card: {sorted(set(flores) - set(claves))}")

    campos = ["key", "nombre", "otros_nombres", "cientifico", "familia", "descripcion", "habitat", "floracion",
              "curiosidad", "toxicidad", "cuidados", "simbolismo", "etimologia", "usos", "grupo", "colores"]
    nombres = [f["nombre"] for f in fichas]
    assert len(nombres) == len(set(nombres)), "two cards share a common name"
    ruta_creditos = AQUI / FOTOS / "creditos.json"
    creditos = json.loads(ruta_creditos.read_text()) if ruta_creditos.exists() else {}
    sin_credito = []
    salida = []
    ruta_numeros = AQUI / "numeracion.json"
    numeros = json.loads(ruta_numeros.read_text()) if ruta_numeros.exists() else {}
    siguiente = max(numeros.values(), default=0) + 1
    for f in sorted(fichas, key=lambda f: (f["familia"], f["cientifico"], f["key"])):
        if f["key"] not in numeros:
            numeros[f["key"]] = siguiente
            siguiente += 1
    assert len(set(numeros.values())) == len(numeros), "two flowers share a number"
    ruta_numeros.write_text(json.dumps(numeros, ensure_ascii=False, indent=1) + "\n")
    for f in sorted(fichas, key=lambda f: numeros[f["key"]]):
        e = {"numero": numeros[f["key"]], **{c: f[c] for c in campos}}
        assert e["grupo"] in ("jardin", "silvestre"), f["key"]
        e["estaciones"] = estaciones(f["floracion"])
        e["color"] = familia_color(f["colores"][0])
        foto = AQUI / FOTOS / f"{slug(f['key'])}.webp"
        sprite = AQUI / "sprites" / f"{slug(f['key'])}.png"
        credito = creditos.get(slug(f["key"])) if foto.exists() else None
        if foto.exists() and not credito:
            sin_credito.append(f["key"])
        e["foto"] = f"miniaturas/{foto.name}" if credito else None
        e["sprite"] = f"sprites/{sprite.name}" if credito and sprite.exists() else None
        e["credito"] = {k: credito[k] for k in ("autor", "licencia", "fuente")} if credito else None
        salida.append(e)

    texto = json.dumps(salida, ensure_ascii=False, indent=1) + "\n"
    (AQUI / "floridex.json").write_text(texto)
    sin_estacion = [e["key"] for e in salida if not e["estaciones"]]
    sin_sprite = sum(e["sprite"] is None for e in salida)
    sin_foto = [e["key"] for e in salida if e["foto"] is None]
    print(f"{len(salida)} cards, {len(sin_foto)} without a reference photo, {sin_sprite} without sprite, no season: {sin_estacion}")
    if sin_credito:
        print(f"photos left out for lack of a credit: {sin_credito}")


if __name__ == "__main__":
    main()
