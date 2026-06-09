# Running & deploying the ClojureWasm bookshelf

The app is **self-contained**: `cljw` is built from source (the pinned ClojureWasm
ref, `-Dwasm` ReleaseSafe; zwasm via the `build.zig.zon` tag pin), and the SPA +
Wasm modules are committed. Local and fly obtain `cljw` the same way — local via
`run_local.sh`, fly via the root `Dockerfile`. SQLite and book-cover colours run
in-process through `cljw`'s Wasm FFI (`wasm/sqlite3.wasm`, `wasm/cover_color.wasm`;
see [`PROVENANCE.md`](./PROVENANCE.md)).

## Local

```sh
cp .envrc.example .envrc        # then fill in GOOGLE_CLIENT_ID and `direnv allow`
./run_local.sh                  # builds cljw on first run (~1 min), serves :8090
```

Needs `git` + Zig 0.16 for the one-time `cljw` build (cached in `.cache/`). Config
comes from the environment (direnv loads `.envrc`); the SQLite store is created +
seeded at `data/books.db` on first run. Sign-in is disabled until `GOOGLE_CLIENT_ID`
is set.

### Rebuilding the artifacts (only when you change them)

```sh
npm ci && npm run release      # rebuild the SPA → resources/public/js/main.js
bash scripts/build_wasm.sh     # rebuild cover_color.wasm + sqlite3.wasm
```

## Deploy to fly.io

The `Dockerfile` + `fly.toml` are at the repo root with a repo-root build context,
so you can deploy by **selecting this repo** ("Deploy from GitHub") or from a
checkout:

```sh
fly launch                                   # first time: creates the app
fly volumes create bookshelf_data --size 1 --region nrt   # persistent SQLite store
fly secrets set GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
fly deploy
```

- **Secrets**: `GOOGLE_CLIENT_ID` is a `fly secret`, never in git or the image.
  Add your deployed origin to the Google console's authorized JavaScript origins.
- **Persistence**: the `[mounts]` volume keeps `data/books.db` across deploys and
  scale-to-zero restarts. Fly Volumes are per-machine NVMe with daily snapshots
  and no built-in replication — fine for a single-machine demo. For HA you would
  add LiteFS / Litestream / Postgres; that's out of scope here.

### Optional: auto-deploy on push (GitHub Actions)

`.github/workflows/fly-deploy.yml` running `flyctl deploy --remote-only` on push,
with a `FLY_API_TOKEN` repo secret
([fly docs](https://fly.io/docs/launch/continuous-deployment-with-github-actions/)).
