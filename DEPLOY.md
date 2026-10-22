# Running and deploying the ClojureWasm bookshelf

The app is one `cljw` process serving the committed SPA, an EDN API and two
committed Wasm modules (`wasm/sqlite3.wasm`, `wasm/cover_color.wasm`; see
[`PROVENANCE.md`](./PROVENANCE.md)). `cljw` itself is the pinned release
tarball from [BuddhiLW/ClojureWasm](https://github.com/BuddhiLW/ClojureWasm/releases),
verified by sha256. Local runs and the image use the same artifact.

## Local

```sh
cp .envrc.example .envrc        # optional: fill in GOOGLE_CLIENT_ID, then `direnv allow`
./run_local.sh                  # serves http://localhost:8090
```

The SQLite store is created and seeded at `data/books.db` on first run.

### Rebuilding the artifacts (only when you change them)

```sh
npm ci && npm run release      # rebuild the SPA: resources/public/js/main.js (commit it)
bash scripts/build_wasm.sh     # rebuild cover_color.wasm + sqlite3.wasm
```

The SPA bundle is committed, so the image has no node or JDK stage.

## Container image

`ghcr.io/clojurewasm/cw-serverless-demo`, tags `main-<7-char sha>` and `latest`.

| | |
|---|---|
| Port | `8080` (`PORT` overrides) |
| Health | `GET /healthz` returns `200 ok`, never touches the DB |
| User | UID/GID `10001`, non-root |
| Root filesystem | read-only is supported |
| Writable paths | `/app/data` (SQLite store, mount a volume) and `/tmp` |
| `GOOGLE_CLIENT_ID` | optional; unset means browse-only, sign-in hidden, writes return 401 |

```sh
docker build -t cw-bookshelf .
docker run --rm --read-only --tmpfs /tmp --user 10001 \
  -v bookshelf-data:/app/data -p 8080:8080 cw-bookshelf
```

## Kubernetes

Served at <https://cljw.buddhilw.com/bookshelf/>. The gateway route must strip
the `/bookshelf` prefix: the server sees `/`, `/api/...`, `/js/...`. Every URL
the browser uses is relative, so the app needs no knowledge of the prefix. Two
things the gateway should do:

- Redirect `/bookshelf` to `/bookshelf/` (relative URLs resolve against the
  trailing slash).
- Optionally send `X-Forwarded-Prefix: /bookshelf`; the session cookie then gets
  `Path=/bookshelf` instead of `Path=/`.

Pod essentials:

```yaml
securityContext:            # pod
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001            # makes the PersistentVolume writable
containers:
  - name: bookshelf
    image: ghcr.io/clojurewasm/cw-serverless-demo:latest
    ports: [{containerPort: 8080}]
    env:
      - name: GOOGLE_CLIENT_ID          # optional
        valueFrom: {secretKeyRef: {name: bookshelf, key: google-client-id, optional: true}}
    securityContext:
      readOnlyRootFilesystem: true
      allowPrivilegeEscalation: false
    readinessProbe: {httpGet: {path: /healthz, port: 8080}}
    livenessProbe:  {httpGet: {path: /healthz, port: 8080}}
    volumeMounts:
      - {name: data, mountPath: /app/data}
      - {name: tmp,  mountPath: /tmp}
volumes:
  - {name: data, persistentVolumeClaim: {claimName: bookshelf-data}}
  - {name: tmp,  emptyDir: {}}
```

Run one replica. SQLite lives on the volume and sessions live in process
memory, so a second replica would neither share sessions nor be safe on a
ReadWriteOnce volume. A restart signs everyone out; the books persist.

## Google sign-in

Sign-in uses Google Identity Services. The client id reaches the browser through
`GET api/config`, read from `GOOGLE_CLIENT_ID` on each request, so setting the
secret and restarting the pod is enough. In the Google Cloud console, the OAuth
client (type: Web application) must list
**`https://cljw.buddhilw.com`** under *Authorized JavaScript origins* (origins
have no path, so not `/bookshelf/`). Add `http://localhost:8090` for local runs.

## CI

- `.github/workflows/image.yml`: on push to `main` (and on demand) builds and
  pushes the image.
- `.github/workflows/cljw-bump.yml`: daily, follows the latest cljw release. It
  verifies the tarball sha256 and its build provenance attestation when the
  release has one, rewrites the pins, commits `chore: cljw <tag>` to `main`,
  then builds the image for that commit.
- `.github/workflows/checks.yml`: `scripts/check_version_pins.sh` keeps every
  cljw version mention and the Dockerfile sha256 pin in agreement.
