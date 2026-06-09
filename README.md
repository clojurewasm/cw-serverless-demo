# ClojureWasm Bookshelf

A small multi-user bookshelf — register with Google, build your shelf, browse
others — served end-to-end by [ClojureWasm](https://github.com/clojurewasm/ClojureWasm)
(`cljw`), a from-scratch Clojure runtime in Zig, no JVM. It is a "serverless-style"
demo: one self-contained binary serving an SPA + JSON API, with **SQLite and
book-cover colours running in-process through `cljw`'s WebAssembly FFI** — no
external database, no JVM, no Babashka.

## What's inside

- **Frontend** (`src/`, ClojureScript + shadow-cljs) — the SPA; the optimized
  release bundle is committed at `resources/public/js/main.js`.
- **Backend** (`server/`, ClojureWasm) — `bookshelf.server` serves the SPA + API;
  `bookshelf.db` talks to SQLite via `(wasm/run "wasm/sqlite3.wasm" …)`;
  `bookshelf.cover` computes cover colours via a Rust Wasm module;
  `bookshelf.auth` verifies Google ID tokens.
- **Wasm modules** (`wasm/`) — SQLite (vendored amalgamation + a first-party
  wrapper) and a hand-written Rust cover-colour module. Origins in
  [`PROVENANCE.md`](./PROVENANCE.md).
- **Storage** — `data/books.db`, created + seeded on first run; persisted on
  fly.io by a volume.

## Run it

```sh
cp .envrc.example .envrc      # fill in GOOGLE_CLIENT_ID, then `direnv allow`
./run_local.sh                # builds cljw from source on first run, serves http://localhost:8090
```

Config (incl. the `GOOGLE_CLIENT_ID` secret) comes from the environment — locally
via direnv, on fly.io via `fly secrets set`. Deploy details (volume, secrets,
repo-select deploy) are in [`DEPLOY.md`](./DEPLOY.md). The repo is self-contained:
only `cljw` is built from source (Zig); the SPA and Wasm modules are committed.

## License

See the ClojureWasm project for runtime licensing. Demo sources here are provided
as-is for illustration.
