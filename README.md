# ClojureWasm Bookshelf

A small multi-user bookshelf served end to end by
[ClojureWasm](https://github.com/BuddhiLW/ClojureWasm) (`cljw`), a Clojure
runtime written in Zig, with no JVM. Anyone can browse every shelf; signing in
with Google gives you a shelf of your own. SQLite and the book-cover colours run
in-process through `cljw`'s WebAssembly FFI: no external database, no JVM, no
Babashka.

Live at <https://cljw.buddhilw.com/bookshelf/>.

## What's inside

- **Frontend** (`src/`, ClojureScript + shadow-cljs): the SPA. The optimized
  release bundle is committed at `resources/public/js/main.js`.
- **Backend** (`server/`, ClojureWasm): `bookshelf.server` serves the SPA and an
  EDN API; `bookshelf.db` talks to SQLite via `(wasm/run "wasm/sqlite3.wasm" ...)`;
  `bookshelf.cover` computes cover colours via a Rust Wasm module;
  `bookshelf.auth` verifies Google ID tokens.
- **Wasm modules** (`wasm/`): SQLite (vendored amalgamation plus a first-party
  wrapper) and a hand-written Rust cover-colour module. Origins in
  [`PROVENANCE.md`](./PROVENANCE.md).
- **Storage**: `data/books.db`, created and seeded on first run.

## Run it

```sh
./run_local.sh                # fetches the pinned cljw release once, serves http://localhost:8090
```

Or as a container:

```sh
docker run --rm -p 8080:8080 -v bookshelf-data:/app/data ghcr.io/clojurewasm/cw-serverless-demo:latest
```

`GOOGLE_CLIENT_ID` is optional. Without it the app is browse-only. Container,
Kubernetes and sign-in details are in [`DEPLOY.md`](./DEPLOY.md).

## License

See the ClojureWasm project for runtime licensing. Demo sources here are provided
as-is for illustration.
