# Wasm module provenance

The bookshelf drives its datastore and book-cover colours through `cljw`'s Wasm
FFI. Both `.wasm` modules in [`wasm/`](./wasm/) are build artifacts of source in
this repo — committed (not gitignored) so the repo deploys self-contained.
Rebuild them with [`scripts/build_wasm.sh`](./scripts/build_wasm.sh).

| `wasm/*.wasm`       | Source                                  | Origin                                                                 | Target               | How it runs                |
|---------------------|-----------------------------------------|------------------------------------------------------------------------|----------------------|----------------------------|
| `cover_color.wasm`  | `wasm-src/cover_color/src/lib.rs`       | **First-party, hand-written Rust** (pinned by `Cargo.lock`)            | `wasm32-unknown-unknown` | `(wasm/call …)` — pure compute |
| `sqlite3.wasm`      | `wasm-src/sqlite/cljw_sqlite.c` + SQLite | **Vendored library + first-party wrapper** (see below)                | `wasm32-wasi`        | `(wasm/run …)` — SQL on stdin → EDN on stdout |

## `sqlite3.wasm` — vendored SQLite + a first-party wrapper

- **Vendored library**: the **SQLite amalgamation v3.53.0.2** (`3530200`,
  public domain), downloaded from `sqlite.org` by `build_sqlite.sh` on first
  build. The raw `sqlite3.c` / `sqlite3.h` are **not committed** (gitignored) —
  they are a reproducible download pinned by `AMALG_VER` in the build script.
- **First-party wrapper**: [`wasm-src/sqlite/cljw_sqlite.c`](./wasm-src/sqlite/cljw_sqlite.c)
  — a small hand-written runner. SQLite's unix file VFS doesn't work on wasi-libc
  (`xRead → ENOTSUP`), so it loads the db file into an in-memory database
  (`sqlite3_deserialize`), runs the SQL from stdin, emits rows as EDN, and
  serialises back to the file only on mutation. Real SQLite, real file
  persistence, no file-VFS.
- **Build**: `zig cc --target=wasm32-wasi` (Zig's bundled wasi-libc — no wasi-sdk
  needed), with `SQLITE_THREADSAFE=0`, `OMIT_LOAD_EXTENSION`, `OMIT_WAL`,
  `TEMP_STORE=3`, `MAX_MMAP_SIZE=0`.

## Rebuilding

```sh
bash scripts/build_wasm.sh   # needs: cargo + wasm32-unknown-unknown target, and zig (for sqlite3.wasm)
```

Rebuilds `cover_color.wasm` (cargo) and `sqlite3.wasm` (downloads the SQLite
amalgamation if absent, then `zig cc`). The persisted database lives at
`data/books.db` (gitignored; created + seeded at runtime by `bookshelf.db/init!`).
