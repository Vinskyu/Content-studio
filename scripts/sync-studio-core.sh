#!/bin/sh
set -e
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/android/app/src/main/assets"
DEST_CORE="$ASSETS_DIR/studio-core"

mkdir -p "$DEST_CORE/.agents/skills" "$DEST_CORE/engine"
cp -f "$PROJECT_ROOT/AGENTS.md" "$DEST_CORE/"
cp -rf "$PROJECT_ROOT/.agents/skills/"* "$DEST_CORE/.agents/skills/"
cp -rf "$PROJECT_ROOT/engine/"* "$DEST_CORE/engine/"

python3 -c '
import zipfile, os
base_dir = "'"$DEST_CORE"'"
zip_path = "'"$ASSETS_DIR/studio-core.zip"'"
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(base_dir):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, base_dir)
            z.write(full, rel)
print("Synced studio-core.zip successfully")
'
rm -rf "$DEST_CORE"
