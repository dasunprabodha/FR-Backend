#!/usr/bin/env bash
# Fetches the Tesseract language data the Sinhala/Tamil OCR pass needs.
#
# Not in version control: these are ~9 MB of binary model files and data/ is
# gitignored, so a fresh clone has none and fr.ocr.multilingual stays disabled
# until this is run.
#
# Sinhala and Tamil come from the main tessdata repository (better accuracy on
# the complex ligatures both scripts use). English comes from tessdata_fast -
# Rekognition is the primary reader for Latin script, so English is loaded only
# to stop Tesseract mis-segmenting the English third of the card, and the 23 MB
# accurate model buys nothing for that.
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/data/tessdata"
mkdir -p "$DEST"

fetch() {
  local repo="$1" lang="$2"
  echo "  $lang <- $repo"
  curl -sSL --fail -o "$DEST/$lang.traineddata" \
    "https://github.com/tesseract-ocr/$repo/raw/main/$lang.traineddata"
}

echo "Fetching Tesseract language data into $DEST"
fetch tessdata      sin
fetch tessdata      tam
fetch tessdata_fast eng

echo
echo "Done:"
ls -lh "$DEST"
echo
echo "Now set fr.ocr.multilingual.enabled=true to use it."
