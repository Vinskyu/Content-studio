#!/bin/sh
set -e
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/android/app/src/main/assets"
DEST_CORE="$ASSETS_DIR/studio-core"

rm -rf "$DEST_CORE"
mkdir -p "$DEST_CORE/.agents/skills" "$DEST_CORE/engine"
cp -f "$PROJECT_ROOT/AGENTS.md" "$DEST_CORE/"
cp -rf "$PROJECT_ROOT/.agents/skills/"* "$DEST_CORE/.agents/skills/"
cp -rf "$PROJECT_ROOT/engine/"* "$DEST_CORE/engine/"

# Jangan ikutkan bytecode/cache lokal ke paket APK.
find "$DEST_CORE" -type d -name "__pycache__" -prune -exec rm -rf {} +
find "$DEST_CORE" -type f -name "*.pyc" -delete
find "$DEST_CORE" -type f -name "*.tmp" -delete
find "$DEST_CORE" -type f -name "*.log" -delete

python3 -c '
import zipfile, os

base_dir = "'"$DEST_CORE"'"
zip_path = "'"$ASSETS_DIR/studio-core.zip"'"

EXCLUDED_STARTS = (
    "workspaces",
    "library/media",
    "library/sessions",
    "library/topic_history",
    "config/providers",
    "config/usage_ledger",
)
EXCLUDED_ENDS = (".tmp", ".log", ".pyc")

count = 0
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(base_dir):
        dirs[:] = [d for d in dirs if d != "__pycache__" and d != ".git"]
        for f in files:
            if f.endswith(EXCLUDED_ENDS) or f.startswith("."):
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, base_dir).replace("\\", "/")
            if any(rel == x or rel.startswith(x + "/") for x in EXCLUDED_STARTS):
                continue
            z.write(full, rel)
            count += 1

print(f"Synced studio-core.zip successfully ({count} files packaged)")
'
rm -rf "$DEST_CORE"
