#!/usr/bin/env bash
# Verifies "Identificar con Floridex" on a connected device or emulator: the
# test sender app (module :emisora, its own package and UID) shares photos
# with ACTION_SEND and a URI read grant, and the text box must report the
# expected flower as captured (not merely offered as a candidate), or no
# flower. Runs once with the app closed and once with it already open.
#
# The photos and the expected names (emisora/src/main/assets/compartir.json)
# come from the training datasets and are not in the repo:
#   uv run --with onnxruntime --with pillow --with numpy python shared/muestra.py <dir>
#
#   scripts/probar-compartir.sh [Floridex apk]
set -euo pipefail
cd "$(dirname "$0")/.."
LISTA=emisora/src/main/assets/compartir.json
[ -f "$LISTA" ] || { echo "missing $LISTA: run shared/muestra.py first (see above)"; exit 1; }
APK=${1:-app/build/outputs/apk/release/app-x86_64-release.apk}
./gradlew -q :emisora:assembleDebug
adb install -r "$APK" >/dev/null
adb install -r emisora/build/outputs/apk/debug/emisora-debug.apk >/dev/null

declare -A ESPERADO
while IFS=$'\t' read -r foto nombre; do ESPERADO[$foto]=$nombre; done < <(
  python3 -c 'import json,sys; [print(f"{k}\t{v}") for k, v in json.load(open(sys.argv[1])).items()]' "$LISTA")

visible() {  # waits up to 30 s for the app's answer naming $1 as the result
  local patron
  if [ "$1" = "No veo ninguna flor" ]; then
    patron='text="No veo ninguna flor'
  else
    # Captured now or before; a name that only shows up as a candidate does not count.
    patron="text=\"(¡Capturada! $1 se ha registrado|$1 ya estaba en tu Floridex)"
  fi
  for _ in $(seq 15); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml | grep -qE "$patron" && return 0
    sleep 2
  done
  return 1
}

fallos=0
for modo in closed open; do
  for foto in "${!ESPERADO[@]}"; do
    [ "$modo" = closed ] && adb shell am force-stop es.floridex
    adb shell am start -n es.floridex.emisora/.Compartir --es foto "$foto" >/dev/null
    if visible "${ESPERADO[$foto]}"; then
      echo "ok    app $modo  $foto -> ${ESPERADO[$foto]}"
    else
      echo "FAIL  app $modo  $foto: «${ESPERADO[$foto]}» did not appear"; fallos=$((fallos + 1))
    fi
    adb shell input keyevent BACK  # closes the text box before the next share
  done
done
[ "$fallos" -eq 0 ] && echo OK || { echo "FAILURES: $fallos"; exit 1; }
