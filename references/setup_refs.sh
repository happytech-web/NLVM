#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REF_DIR="$ROOT/references"
mkdir -p "$REF_DIR"

clone_ref () {
  local url="$1" path="$2" rev="${3:-}"
  if [ ! -d "$REF_DIR/$path/.git" ]; then
    git clone "$url" "$REF_DIR/$path"
  fi
  if [ -n "$rev" ]; then
    (cd "$REF_DIR/$path" && git fetch --all && git checkout "$rev")
  fi
}

# 这里加你要的参考仓库；可加 commit/tag 锁定版本
clone_ref "https://gitlab.eduxiji.net/educg-group-26172-2487152/T202410006203618-2881.git" "BUAA" ""
echo "References ready under $REF_DIR"
