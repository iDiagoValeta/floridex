"""Turns each flower thumbnail into a Floridex sprite: pixel art in the four
greens of a classic handheld screen, with a transparent background.

    uv run --with "rembg[cpu]" --with pillow python shared/sprites.py

Reads `miniaturas2/<slug>.webp` and writes `sprites/<slug>.png` (48 x 48,
indexed). The background is cut out with rembg (U2-Net, on the CPU), so a
locked entry can show the flower's own silhouette. A sprite is a derived
work of its photo (CC BY-SA ones included): it carries the photo's credit
(floridex.py) and is dropped when its photo goes. Only thumbnails without a
sprite are processed; pass --todo to redo them all. Run `floridex.py`
afterwards so the cards point at the new sprites.
"""

import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter
from rembg import new_session, remove

AQUI = Path(__file__).resolve().parent
# Reference photos, one per flower (240 px square webp), made by ml/miniaturas.py.
FOTOS = "miniaturas2"
LADO = 48
# Classic handheld LCD, darkest to lightest.
VERDES = [(15, 56, 15), (48, 98, 48), (139, 172, 15), (155, 188, 15)]
# 4 x 4 ordered dither, so mid tones read as texture instead of flat bands.
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def limpiar(mascara: list[list[bool]]) -> list[list[bool]]:
    """Keeps the flower: drops specks smaller than a tenth of the largest piece."""
    n = len(mascara)
    pieza = [[-1] * n for _ in range(n)]
    areas = []
    for y in range(n):
        for x in range(n):
            if not mascara[y][x] or pieza[y][x] >= 0:
                continue
            pila, area = [(y, x)], 0
            pieza[y][x] = len(areas)
            while pila:
                cy, cx = pila.pop()
                area += 1
                for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < n and 0 <= nx < n and mascara[ny][nx] and pieza[ny][nx] < 0:
                        pieza[ny][nx] = len(areas)
                        pila.append((ny, nx))
            areas.append(area)
    if not areas:
        return mascara
    minimo = max(areas) / 10
    return [[pieza[y][x] >= 0 and areas[pieza[y][x]] >= minimo for x in range(n)] for y in range(n)]


def sprite(origen: Image.Image, sesion) -> Image.Image:
    origen = origen.convert("RGB")
    recorte = remove(origen, session=sesion)  # RGBA
    alfa = recorte.getchannel("A")
    cubre = sum(1 for a in alfa.getdata() if a > 128) / (alfa.width * alfa.height)
    if cubre > 0.8 or cubre < 0.03:
        # No clear subject: a round lens on the centre of the photo instead.
        lado = min(origen.size)
        x0, y0 = (origen.width - lado) // 2, (origen.height - lado) // 2
        # Busy scenes (meadows, bunches) turn to noise at 48 px: smooth them first.
        recorte = origen.crop((x0, y0, x0 + lado, y0 + lado)).filter(ImageFilter.MedianFilter(7)).convert("RGBA")
        lente = Image.new("L", recorte.size, 0)
        ImageDraw.Draw(lente).ellipse((lado * 0.06, lado * 0.06, lado * 0.94, lado * 0.94), fill=255)
        recorte.putalpha(lente)
        alfa = lente
    caja = alfa.point(lambda a: 255 if a > 128 else 0).getbbox() or (0, 0, *recorte.size)
    recorte = recorte.crop(caja)
    # Square canvas, with a pixel of margin for the outline.
    w, h = recorte.size
    s = (LADO - 2) / max(w, h)
    recorte = recorte.resize((max(1, round(w * s)), max(1, round(h * s))), Image.Resampling.BOX)
    lienzo = Image.new("RGBA", (LADO, LADO))
    lienzo.paste(recorte, ((LADO - recorte.width) // 2, (LADO - recorte.height) // 2))

    lum = lienzo.convert("L")
    opaco = limpiar([[lienzo.getpixel((x, y))[3] > 128 for x in range(LADO)] for y in range(LADO)])
    valores = sorted(lum.getpixel((x, y)) for y in range(LADO) for x in range(LADO) if opaco[y][x])
    salida = Image.new("P", (LADO, LADO), 0)
    paleta = [0, 0, 0] + [c for v in VERDES for c in v]
    salida.putpalette(paleta + [0] * (768 - len(paleta)))
    salida.info["transparency"] = 0
    if not valores:
        return salida
    # Stretch each sprite's own range so dark and pale flowers both use all four greens.
    k = len(valores) // 20
    bajo, alto = valores[k], valores[len(valores) - 1 - k]
    rango = max(1, alto - bajo)
    for y in range(LADO):
        for x in range(LADO):
            if not opaco[y][x]:
                # Outline: a dark pixel next to the flower.
                vecino = any(
                    0 <= y + dy < LADO and 0 <= x + dx < LADO and opaco[y + dy][x + dx]
                    for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0))
                )
                if vecino:
                    salida.putpixel((x, y), 1)
                continue
            t = min(1, max(0, (lum.getpixel((x, y)) - bajo) / rango))
            umbral = (BAYER[y % 4][x % 4] + 0.5) / 16 - 0.5
            # The two lightest greens are close, so the light end gets more room.
            nivel = min(3, max(0, int(t * 3.4 + umbral * 0.8 + 0.3)))
            salida.putpixel((x, y), 1 + nivel)
    return salida


def main():
    todo = "--todo" in sys.argv
    destino = AQUI / "sprites"
    destino.mkdir(exist_ok=True)
    # Sprites of photos that are gone (replaced or withdrawn) go too.
    fotos = {f.stem for f in (AQUI / FOTOS).glob("*.webp")}
    huerfanos = [str(f) for f in destino.glob("*.png") if f.stem not in fotos]
    if huerfanos:
        subprocess.run(["gio", "trash", *huerfanos], check=True)
        print(f"{len(huerfanos)} sprites without a photo moved to the trash")
    pendientes = [f for f in sorted((AQUI / FOTOS).glob("*.webp")) if todo or not (destino / f"{f.stem}.png").exists()]
    if not pendientes:
        print("every thumbnail already has a sprite")
        return
    sesion = new_session("u2net")
    for i, f in enumerate(pendientes, 1):
        sprite(Image.open(f), sesion).save(destino / f"{f.stem}.png", optimize=True)
        if i % 50 == 0:
            print(f"{i}/{len(pendientes)}")
    print(f"{len(pendientes)} sprites written to {destino}")


if __name__ == "__main__":
    main()
