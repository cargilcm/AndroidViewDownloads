#!/bin/bash
# Fetch square-o icons directly from GitHub release / raw directory
DEST_DIR="app/src/main/assets/icons/square-o"
mkdir -p "$DEST_DIR"

echo "Downloading square-o SVGs..."
curl -s "https://api.github.com/repos/dmhendricks/file-icon-vectors/contents/dist/icons/square-o" | \
grep -oP '"download_url": "\K[^"]+' | \
while read -r url; do
    echo "Fetching $url"
    curl -s -O --output-dir "$DEST_DIR" "$url"
done
