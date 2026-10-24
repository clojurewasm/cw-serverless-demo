# ClojureWasm bookshelf demo: container image (root build context).
#
# cljw comes from the RELEASE tarball, verified against a pinned sha256 (no Zig
# source build). The release binary is ReleaseSafe, -Dwasm, -Dcpu=baseline, so
# it runs on any x86_64 node. The SPA bundle (resources/public/js/main.js) is
# COMMITTED, as this repo has always done, so the image needs no node or JDK
# stage; rebuild it with `npm ci && npm run release` when src/ changes.
#
# Runtime contract (see DEPLOY.md):
#   - listens on :8080, GET /healthz -> 200 "ok" (no DB access)
#   - runs as UID/GID 10001, works with a read-only root filesystem
#   - writable paths: /app/data (SQLite store, mount a volume) and /tmp
#   - GOOGLE_CLIENT_ID is optional; unset means browse-only

# --- Stage 1: fetch + verify the cljw release binary ---
FROM debian:bookworm-slim AS cljw
ARG CLJW_VERSION=v1.15.4
ARG CLJW_SHA256=1fa934c46229aeaaef777a38855930207980f55efc4330f22e73fcec61ec571f
ARG CLJW_REPO=BuddhiLW/ClojureWasm
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    if [ -n "${TARGETARCH:-}" ] && [ "${TARGETARCH}" != "amd64" ]; then \
      echo "cljw publishes a linux-x86_64 binary only; TARGETARCH=${TARGETARCH}" >&2; exit 1; \
    fi; \
    curl -fsSL -o /tmp/cljw.tar.gz \
      "https://github.com/${CLJW_REPO}/releases/download/${CLJW_VERSION}/cljw-linux-x86_64.tar.gz"; \
    echo "${CLJW_SHA256}  /tmp/cljw.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/cljw.tar.gz -C /usr/local/bin cljw; \
    chmod 0755 /usr/local/bin/cljw; \
    /usr/local/bin/cljw --version

# --- Stage 2: runtime (cljw + the committed app: server + static SPA + wasm) ---
FROM debian:bookworm-slim
# ca-certificates: auth.clj verifies Google ID tokens over HTTPS (tokeninfo).
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates && rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent \
        --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=cljw /usr/local/bin/cljw /usr/local/bin/cljw
# Everything under /app is root-owned and read-only to the app user, except
# data/, the SQLite store db.clj creates and seeds on first run. In Kubernetes a
# PersistentVolume mounts over it (fsGroup 10001 makes it writable).
COPY deps.edn ./deps.edn
COPY server/ ./server/
COPY resources/ ./resources/
COPY wasm/ ./wasm/
RUN mkdir -p /app/data && chown 10001:10001 /app/data && chmod 0770 /app/data

# HOME/CLJW_HOME point at /tmp so nothing ever tries to write under / (deps.edn
# has no top-level deps, so cljw fetches nothing at startup anyway).
ENV PORT=8080 \
    CLJW_FS_ROOT=/app \
    HOME=/tmp \
    CLJW_HOME=/tmp/.cljw
USER 10001:10001
VOLUME ["/app/data"]
EXPOSE 8080
# bookshelf.server takes the port as its first arg; run-server binds 0.0.0.0.
CMD ["sh", "-c", "exec cljw -M:cljw -m bookshelf.server \"$PORT\""]
