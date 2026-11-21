# Development

How the three parts are built, tested and regenerated. Contribution rules are in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Model (`ml/`)

DINOv2-small (`facebook/dinov2-small`) fine-tuned with PyTorch on Oxford Flowers 102 and 17, tf_flowers, Swedish Flowers, an iNaturalist 2021 subset, Pl@ntNet-300K flower photos and extra Creative Commons garden photos from the iNaturalist API (training only), plus a "no flower" class from Imagenette, Caltech-101 and SUN397 without their flower, garden, greenhouse, florist and meadow categories. Labels naming the same species across datasets share one class (`taxonomy.py`, `prepare2.py`). Train and validation photos that are near duplicates of any test photo (perceptual hash) are dropped.

```bash
cd ml
uv sync
python prepare.py && python prepare_neg.py   # download step: see the URLs inside
python prepare2.py                            # adds iNaturalist 2021 and Pl@ntNet-300K
python fetch_inat.py --target 400             # extra garden photos, training only
python fetch_inat.py --target 40 --out data/raw/inat_eval --excluir data/raw/inat_api
python dedup_eval.py && python prepare_extra.py
python train.py --manifest data/manifest2.jsonl --classes data/classes2.json --epochs 12 --out runs/v2-small
python train.py --model runs/v2-small/best --manifest data/manifest2.jsonl --classes data/classes2.json \
    --epochs 3 --lr 2e-5 --head-lr 2e-4 --out runs/v2-ft
python calibrate.py runs/v2-ft/best runs/v2-ft/calibrado --manifest data/manifest2.jsonl --classes data/classes2.json
python export.py --run runs/v2-ft/calibrado --out models/floridex --manifest data/manifest2.jsonl --classes data/classes2.json
python eval_jardin.py models/floridex
```

- `calibrate.py` fits one temperature on the validation split and folds it into the classifier weights, so the shown percentage tracks how often the model is right (test ECE 0.013). Below 70 % the apps ask instead of capturing.
- `export.py` exports to ONNX, quantizes per channel to int8 and checks every test photo with both models (`parity.json`). It fails if Oxford 102, Oxford 17, tf_flowers, Swedish or the non-flower set drops below 95 % after quantization; iNaturalist and Pl@ntNet are the harder benchmarks and have no such floor.
- `eval_comparado.py` scores a model on every benchmark, optionally only on the classes an older model knows, for a fair comparison.
- `eval_jardin.py` is the garden benchmark: iNaturalist observations never used in training, near duplicates removed by `dedup_eval.py`.

To ship a model, copy `config.json`, `preprocessor_config.json` and `onnx/model_quantized.onnx` into `shared/modelo/` and run `shared/floridex.py`.

## Cards, photos and sprites (`shared/`)

- `flores.json` and `fichas2/nuevas_unidas.json` are the cards, written to `fichas2/ESPEC.md`.
- `miniaturas2/` holds one reference photo per flower and `creditos.json` with each author, licence and observation. They come from `ml/fetch_inat.py --fichas` (research-grade iNaturalist photos under CC0, CC BY or CC BY-SA, most voted first) and `ml/miniaturas.py`, which picks the photo the model is most confident about with the flower filling its centre. Two flowers have no licensed photo and use a generic pixel flower.
- `sprites.py` cuts each reference photo out of its background (rembg, U2-Net on the CPU) into a 48 px sprite in the four greens: the pixel view of a captured flower, and the silhouette shown until then.
- `floridex.py` builds `floridex.json`, one entry per label of the shipped model except "no flower", with the credits, the season and colour filters, and asserts one card per label. Numbers are frozen in `numeracion.json`: flowers a later model adds take the next free numbers, and a number is never reused.

```bash
uv run --with "rembg[cpu]" --with pillow python shared/sprites.py
python3 shared/floridex.py
```

Both apps list the entries whose key is a label of the shipped model, so a model that knows more flowers is a file drop.

## Web (`web/`)

```bash
cd web
pnpm install
pnpm dev            # http://localhost:5190
pnpm build          # static site in dist/
```

`scripts/copy-assets.mjs` copies ONNX Runtime and the data from `../shared` into `public/` (generated, not committed); nothing is fetched from a CDN. The classifier worker prepares each photo as in training (`src/preparar.ts`: PIL's bicubic resize, Python's crop rounding, no re-encoding) and runs the model on that tensor. Progress lives in IndexedDB (database `floridex`). The live camera needs a secure context (https or localhost).

Offline: the service worker precaches the app, the cards, sprites, photos and the model's JSON files on the first visit; transformers.js keeps the weights in `transformers-cache`. The cache name hashes the files' contents, so a changed file reaches existing users with the next build, and the build fingerprints the model so a retrained one is fetched again. The static host must answer 404 for missing files: transformers.js probes optional model files.

### End-to-end checks

Real photos through the UI in Chromium and WebKit. The sample photos come from the datasets' test splits and are not published; generate them, and the Android instrumented-test sample, with:

```bash
uv run --with onnxruntime --with pillow --with numpy python shared/muestra.py <dir>
```

`<dir>` also needs `camara.y4m`, a clip of a flower for Chromium's fake webcam. Then, against `pnpm dev` or `pnpm build && pnpm preview`:

```bash
node e2e/floridex.mjs <url> <dir> <screenshots dir>    # the index: capture, persistence, choices, search, filters
OFFLINE=1 node e2e/verificar.mjs <url> <dir> <screenshots dir>   # parity with Python, camera, offline
node e2e/lectura.mjs <url> <dir>                       # every way out of a reading returns to the live camera
node e2e/capturas.mjs <dir> <screenshots dir> <url>    # screenshots of the whole flow
```

`verificar.mjs` requires the same decision as Python (capture, ask or no flower) unless Python's confidence is within 0.05 of 0.7, the same top answer unless both were unsure, and at least 95 % equal answers. The iPhone pass runs in WebKit; on a machine without WebKit's system libraries (`sudo npx playwright install-deps webkit`) it falls back to Chromium with iPhone emulation and the verdict is `WEBKIT NOT RUN` (exit code 2). To run WebKit without sudo, install it into its own folder, drop `libavif16`, `libgav1-1` and `libyuv0` (from `apt-get download`) into its `minibrowser-wpe/sys/lib`, and run with `PLAYWRIGHT_BROWSERS_PATH=<folder>` and `PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1`.

## Android (`android/`)

```bash
cd android
./gradlew assembleRelease            # app/build/outputs/apk/release/app-<abi>-release.apk
./gradlew connectedDebugAndroidTest  # needs a device or emulator and the shared/muestra.py sample
```

Gradle copies the data from `../shared` into generated assets before each build. Release APKs are signed with the key in `android/keystore.properties` (never committed: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or, without it, with the local debug key. Progress lives in the app's private files (`files/floridex/progreso.json`, written atomically, and one JPEG per capture), excluded from Android backup.

The instrumented tests classify the held-out sample on the device and check agreement with Python (at least 97 %, and the same capture or ask decision), accuracy no more than one point below Python's, one card per label, the whole index flow from an empty collection (`FloridexTest`, which sets the phone's collection aside and restores it) and every way out of a reading (`LecturaTest`). `CapturasFlujo` takes screenshots of the flow only when asked (`-e capturas 1`).

`scripts/probar-compartir.sh` checks «Identificar con Floridex»: `emisora/`, a minimal third-party app with its own FileProvider, shares sample photos and the app must report the expected flower as captured, or no flower, with the app closed and open.
