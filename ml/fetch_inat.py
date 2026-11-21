"""Extra TRAINING photos from iNaturalist for v1 classes with few examples.

Oxford 102 trains on 10 to 20 photos per class, too few: an orange gerbera
came out as a sunflower (524 training photos). This fetches up to TARGET
Creative Commons photos per class from the public iNaturalist API (one API call
per second), research grade first and then casual observations, which is where
cultivated garden flowers live. Photos go to training only: evaluation stays
on the published benchmarks. Attribution is kept in creditos.jsonl.

    python fetch_inat.py
    python fetch_inat.py --solo "barbeton daisy=Gerbera" --target 900   # top up one class
    python fetch_inat.py --solo "barbeton daisy=Gerbera" --target 150 --out data/raw/inat_eval --excluir data/raw/inat_api
    # catalogue photos: the most voted research-grade photos under licences that
    # allow redistribution with credit, for every card
    python fetch_inat.py --fichas ../shared/floridex.json --target 10 --out data/raw/miniaturas_inat \
        --licencias cc0,cc-by,cc-by-sa --solo-research --orden votes
"""
import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

OUT = Path("data/raw/inat_api")
TARGET = 160
LICENCIAS = "cc0,cc-by,cc-by-nc,cc-by-sa,cc-by-nc-sa,cc-by-nd,cc-by-nc-nd"
UA = {"User-Agent": "floridex-dataset-builder/1.0"}

# v1 class -> iNaturalist taxon name, as iNaturalist currently spells it
# (garden hybrids carry "×"; Anemone hupehensis is now Eriocapitella). Cultivars, mixed groups and genera that
# overlap another class are left out on purpose.
TAXONES = {
    "lotus": "Nelumbo nucifera", "siam tulip": "Curcuma alismatifolia", "carnation": "Dianthus caryophyllus",
    "alpine sea holly": "Eryngium alpinum", "anthurium": "Anthurium andraeanum", "artichoke": "Cynara cardunculus",
    "ball moss": "Wallisia cyanea", "balloon flower": "Platycodon grandiflorus", "barbeton daisy": "Gerbera jamesonii",
    "bee balm": "Monarda didyma", "bird of paradise": "Strelitzia reginae", "black-eyed susan": "Rudbeckia hirta",
    "blackberry lily": "Iris domestica", "blanket flower": "Gaillardia", "bougainvillea": "Bougainvillea",
    "bromelia": "Guzmania", "californian poppy": "Eschscholzia californica", "camellia": "Camellia japonica",
    "canna lily": "Canna", "canterbury bells": "Campanula medium", "cape flower": "Nerine bowdenii",
    "cautleya spicata": "Cautleya spicata", "clematis": "Clematis", "columbine": "Aquilegia vulgaris",
    "cyclamen": "Cyclamen persicum", "desert-rose": "Adenium obesum", "english marigold": "Calendula officinalis",
    "fire lily": "Gloriosa superba", "frangipani": "Plumeria rubra", "garden phlox": "Phlox paniculata",
    "gaura": "Oenothera lindheimeri", "gazania": "Gazania rigens", "geranium": "Pelargonium × hybridum",
    "giant white arum lily": "Zantedeschia aethiopica", "globe thistle": "Echinops ritro",
    "grape hyacinth": "Muscari armeniacum", "great masterwort": "Astrantia major",
    "hard-leaved pocket orchid": "Paphiopedilum micranthum", "hibiscus": "Hibiscus × rosa-sinensis",
    "hippeastrum": "Hippeastrum", "japanese anemone": "Eriocapitella hupehensis", "king protea": "Protea cynaroides",
    "lenten rose": "Helleborus orientalis", "love in the mist": "Nigella damascena", "magnolia": "Magnolia",
    "mallow": "Malva sylvestris", "marigold": "Tagetes", "mexican aster": "Cosmos bipinnatus",
    "mexican petunia": "Ruellia simplex", "monkshood": "Aconitum napellus", "moon orchid": "Phalaenopsis",
    "morning glory": "Ipomoea purpurea", "osteospermum": "Osteospermum", "passion flower": "Passiflora caerulea",
    "peruvian lily": "Alstroemeria", "petunia": "Petunia × atkinsiana", "pincushion flower": "Sixalix atropurpurea",
    "pink primrose": "Oenothera speciosa", "poinsettia": "Euphorbia pulcherrima", "purple coneflower": "Echinacea purpurea",
    "red ginger": "Alpinia purpurata", "ruby-lipped cattleya": "Cattleya labiata", "silverbush": "Convolvulus cneorum",
    "snapdragon": "Antirrhinum majus", "stemless gentian": "Gentiana acaulis", "sweet pea": "Lathyrus odoratus",
    "sweet william": "Dianthus barbatus", "sword lily": "Gladiolus", "thorn apple": "Datura", "toad lily": "Tricyrtis hirta",
    "tree mallow": "Malva arborea", "tree poppy": "Romneya coulteri", "trumpet creeper": "Campsis radicans",
    "wallflower": "Erysimum × cheiri", "watercress": "Tropaeolum majus", "bearded iris": "Iris × germanica",
    "fritillary": "Fritillaria meleagris", "daffodil": "Narcissus pseudonarcissus", "windflower": "Anemone coronaria",
    "field forget-me-not": "Myosotis arvensis", "bluebell": "Hyacinthoides non-scripta", "snowdrop": "Galanthus nivalis",
    "spring crocus": "Crocus vernus", "tiger lily": "Lilium lancifolium",
}

_ultima = [0.0]


def api(ruta, **q):
    espera = 1.0 - (time.time() - _ultima[0])
    if espera > 0:
        time.sleep(espera)
    _ultima[0] = time.time()
    # v2 with an explicit field list: the full v1 payload took over 90 s per page.
    url = f"https://api.inaturalist.org/v2/{ruta}?{urllib.parse.urlencode(q)}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def _norma(n):
    return re.sub(r"\s+", " ", n.replace("×", " ").replace(" x ", " ")).strip().lower()


def taxon_id(nombre):
    """Exact name, or a synonym iNaturalist has moved to another genus
    (Anemone pulsatilla -> Pulsatilla vulgaris), including its autonym
    (Sedum rupestre rupestre)."""
    buscado = _norma(nombre)
    autonimo = f"{buscado} {buscado.split()[-1]}"
    for t in api("taxa", q=nombre, per_page=10, fields="id,name,matched_term")["results"]:
        if _norma(t["name"]) == buscado or _norma(t.get("matched_term") or "") in (buscado, autonimo):
            return t["id"]
    return None


def candidatos(cientifico):
    """Names to try for a card's scientific name, most specific first:
    "Dahlia 'Bishop of Llandaff'" -> Dahlia; "Rosa spp." -> Rosa;
    "Celosia argentea var. plumosa" -> itself, then Celosia argentea."""
    n = re.sub(r"'[^']*'", "", cientifico).replace("spp.", "").strip()
    partes = n.split()
    nombres = [n]
    if len(partes) > 2:
        nombres.append(" ".join(partes[:3] if partes[1] == "×" else partes[:2]))
    nombres.append(partes[0])
    return list(dict.fromkeys(nombres))


def bajar(trabajo):
    url, destino = trabajo
    if destino.exists():
        return True
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
            destino.write_bytes(r.read())
        return True
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--solo", action="append", default=[], help='"class=iNaturalist taxon", repeatable')
    ap.add_argument("--target", type=int, default=TARGET)
    ap.add_argument("--out", default=str(OUT))
    # Held-out evaluation sets must not share observations with training photos.
    ap.add_argument("--excluir", default=None, help="folder whose <class>/<obs id>.jpg files are skipped")
    ap.add_argument("--fichas", default=None, help="cards JSON: one taxon per card, from its scientific name")
    ap.add_argument("--licencias", default=LICENCIAS)
    ap.add_argument("--solo-research", action="store_true")
    ap.add_argument("--orden", default=None, help="iNaturalist order_by, e.g. votes")
    args = ap.parse_args()
    if args.fichas:
        # A v1 class keeps its curated taxon; the rest come from the card.
        taxones = {f["key"]: [TAXONES[f["key"]]] if f["key"] in TAXONES else candidatos(f["cientifico"])
                   for f in json.load(open(args.fichas))}
    elif args.solo:
        taxones = {k: [v] for k, v in (x.split("=", 1) for x in args.solo)}
    else:
        taxones = {k: [v] for k, v in TAXONES.items()}
    out, target = Path(args.out), args.target
    out.mkdir(parents=True, exist_ok=True)
    creditos = open(out / "creditos.jsonl", "a")
    for clave, nombres in taxones.items():
        carpeta = out / clave
        carpeta.mkdir(exist_ok=True)
        vistos = {f.stem for f in (Path(args.excluir) / clave).glob("*.jpg")} if args.excluir else set()
        if len(list(carpeta.glob("*.jpg"))) >= target:
            continue
        tid = nombre = None
        for nombre in nombres:
            tid = taxon_id(nombre)
            if tid:
                break
        if not tid:
            print(f"no taxon: {clave} ({', '.join(nombres)})")
            continue
        fotos = []
        for calidad in ("research",) if args.solo_research else ("research", "casual,needs_id"):
            pagina = 1
            while len(fotos) < target and pagina <= target // 200 + 3:
                orden = {"order_by": args.orden} if args.orden else {}
                r = api("observations", taxon_id=tid, photos="true", photo_license=args.licencias, quality_grade=calidad,
                        per_page=200, page=pagina, fields="id,photos.url,photos.license_code,photos.attribution", **orden)
                for o in r["results"]:
                    if not o.get("photos") or len(fotos) >= target or str(o["id"]) in vistos:
                        continue
                    f = o["photos"][0]
                    fotos.append((o["id"], f["url"].replace("square", "medium"), f.get("attribution"), f.get("license_code"), calidad))
                if len(r["results"]) < 200:
                    break
                pagina += 1
            if len(fotos) >= target:
                break
        trabajos = [(u, carpeta / f"{oid}.jpg") for oid, u, *_ in fotos]
        with ThreadPoolExecutor(24) as ex:  # photo files come from a public S3 bucket; the API stays at 1 call per second
            ok = list(ex.map(bajar, trabajos))
        for (oid, u, atr, lic, cal), bien in zip(fotos, ok):
            if bien:
                creditos.write(json.dumps({"clase": clave, "obs": oid, "url": u, "atribucion": atr, "licencia": lic, "calidad": cal}, ensure_ascii=False) + "\n")
        creditos.flush()
        print(f"{clave}: {sum(ok)} photos ({nombre}, taxon {tid})", flush=True)


if __name__ == "__main__":
    main()
