# serverless-v2 — Bookshelf on ClojureWasm (design)

> **This is the PRE-IMPLEMENTATION design memo, kept as a record of the original
> intent. The app has moved past it in three ways, so do not read this file as
> documentation of what runs today — `README.md` is that.**
>
> | This memo says | What the app does |
> |---|---|
> | no Docker, local only | `Dockerfile` + `fly.toml`; deployed to fly.io, auto-deploy on push |
> | accounts: username + salted hash | Google OIDC (`server/bookshelf/auth.clj`) |
> | JSON API | EDN API (`server/bookshelf/server.clj`) |
>
> The rest — cljw as the web server, the Rust cover-colour module, SQLite
> compiled to `wasm32-wasi` as the datastore — is accurate and is what the demo
> is for. Kept rather than deleted because the *reasoning* below (why a wasm
> SQLite instead of a Clojure-side store, why Ring-style) is still the reasoning.

A small "bookshelf" web app — Google-Cloud-Bookshelf-like — that runs on **native
`cljw`** (no JVM). It is the CFP's "real edge app anyone can try",
demonstrating:

1. **ClojureWasm as the web server.** The HTTP server is `cljw.http.server/run-server`
   (Ring-style), serving a ClojureScript SPA + a JSON API, all on the cljw binary.
2. **Polyglot via the Wasm FFI.** Two other-language wasm assets run *inside* the app:
   - a **cover-colour** module (Rust → `wasm32-unknown-unknown`, called with `wasm/call`)
     that deterministically computes a book's cover colour from its title — "another
     language's asset runs in a real app".
   - the **datastore** is real **SQLite compiled to `wasm32-wasi`** (C → wasi-sdk),
     driven via `wasm/run` against a DB file in a preopened dir — a genuine DB call
     where the DB engine itself is a wasm module.

## Feature set (CFP spec ∪ Google Bookshelf)

- **Accounts**: log in with Google (OIDC; cookie session). *(The memo originally
  planned a local username + salted hash — see the note at the top.)*
- **Own shelf**: CRUD books (title, author, description, labels, favourite).
- **Other shelves**: view read-only; **copy** a book from another shelf to your own.
- **Search**: across all books (title / author / label).
- **Cover colour**: computed by the Rust wasm module (stable per title).

## Architecture

```
browser  ──HTTP──>  cljw.http.server (run-server)  ──wasm/call──>  cover_color.wasm (Rust)
  (cljs SPA)             |  routes /api/*           ──wasm/run───>  sqlite3.wasm (C→wasi)
                         |                                              │ preopen data/
                         └── serves resources/public (built SPA)       └─ books.db
```

- **Backend** (`server/bookshelf/*.clj`, run by `cljw`): routing, session, the
  `db` namespace (wraps `wasm/run` on sqlite3.wasm — builds SQL, parses rows),
  and the `cover` namespace (wraps `wasm/call` on cover_color.wasm).
- **Frontend** (`src/bookshelf/*.cljs`): same stack as playground-v2 — shadow-cljs,
  React, Mantine, phosphor, factorhouse/rfx + hsx.
- **No Docker, no JVM, no cloud.** `data/books.db` is a local SQLite file; the SQLite
  engine runs as wasm. Everything starts with `cljw` + `bb`/`shadow-cljs` locally.

## DB-via-wasm contract

`db.clj` calls:
```clojure
(wasm/run "wasm/sqlite3.wasm"
          {:args ["sqlite3" "/data/books.db" "-json" SQL]
           :dirs [["data" "/data"]]})
```
and parses `:out` as JSON rows. SQL is built with parameterised escaping in Clojure
(the sqlite CLI takes one SQL string; we quote values safely). One preopened dir
(`data/` → guest `/data`) holds the persistent DB file.

## Why this shape

- Uses the cljw capabilities that exist today: `run-server`, `wasm/call`, the new
  `wasm/run` (ADR-0124) with `:dirs` preopen.
- The DB engine being a wasm module is the strongest form of the polyglot claim:
  not just a pure-compute kernel, but a real stateful C library running in the
  sandbox and persisting to a real file.
- Local-only, Docker-free, matches the user's constraint.

## Build order (frontend-driven, then back)

1. Backend skeleton on cljw `run-server` with EDN persistence (get the app working).
2. Frontend SPA (shelves, CRUD, search) against the JSON API.
3. Swap EDN → sqlite3.wasm via `wasm/run` (real DB-via-wasm).
4. Cover-colour Rust wasm via `wasm/call`.
5. Verify end-to-end in Chrome (my-playwright).
