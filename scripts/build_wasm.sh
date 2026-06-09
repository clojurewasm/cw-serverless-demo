#!/usr/bin/env bash
# Build the bookshelf's wasm assets into wasm/.
#   - cover_color.wasm : Rust → wasm32-unknown-unknown (pure compute), via wasm/call
#   - sqlite3.wasm     : C SQLite → wasm32-wasi (wasi-sdk), via wasm/run  [optional]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(pwd)/wasm"; mkdir -p "$OUT"

echo "==> rust: cover_color"
( cd wasm-src/cover_color && cargo build --release --target wasm32-unknown-unknown )
cp wasm-src/cover_color/target/wasm32-unknown-unknown/release/cover_color.wasm "$OUT/cover_color.wasm"
echo "    -> wasm/cover_color.wasm ($(wc -c < "$OUT/cover_color.wasm") bytes)"

if [ -f wasm-src/sqlite/build_sqlite.sh ]; then
  echo "==> sqlite (wasi-sdk)"
  bash wasm-src/sqlite/build_sqlite.sh "$OUT"
fi

echo "done. wasm/:"; ls -la "$OUT"/*.wasm 2>/dev/null
