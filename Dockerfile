# ClojureWasm bookshelf demo — container image (self-contained, root-context).
#
# Symmetric with run_local.sh: cljw is built FROM SOURCE (clone the pinned
# ClojureWasm ref + `zig build -Dwasm -Doptimize=ReleaseSafe`; zwasm resolves via
# ClojureWasm's build.zig.zon tag pin — no sibling checkout). Build context is
# THIS repo root, so "Deploy from GitHub" / `fly launch` works by selecting the
# repo. SQLite + book-cover colours run in-process via cljw's Wasm FFI (the
# committed wasm/ modules). Secrets come from the environment (fly secrets).
# See DEPLOY.md.

# --- Stage 1: build cljw (linux, ReleaseSafe, -Dwasm) from source ---
FROM debian:bookworm-slim AS build
ARG ZIG_VERSION=0.16.0
ARG CLJW_REF=cw-from-scratch
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl xz-utils git && rm -rf /var/lib/apt/lists/*
RUN arch="$(uname -m)" && \
    curl -fsSL "https://ziglang.org/download/${ZIG_VERSION}/zig-${arch}-linux-${ZIG_VERSION}.tar.xz" \
      | tar -xJ -C /opt && ln -s /opt/zig-*-linux-*/zig /usr/local/bin/zig
# -Dcpu=baseline is REQUIRED, not an optimization knob: `zig build` defaults to the
# build host's native CPU, so a binary built on a fly remote builder with newer
# instructions (AVX etc.) crashes with SIGILL ("Illegal instruction", exit 132) on
# a shared-cpu run machine that lacks them. baseline = portable across fly CPUs.
RUN git clone --branch "${CLJW_REF}" --depth 1 \
      https://github.com/clojurewasm/ClojureWasm.git /src/cljw && \
    cd /src/cljw && zig build -Dwasm -Doptimize=ReleaseSafe -Dcpu=baseline && \
    cp zig-out/bin/cljw /usr/local/bin/cljw

# --- Stage 2: runtime (cljw + the committed app: server + static SPA + wasm) ---
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /usr/local/bin/cljw /usr/local/bin/cljw
COPY deps.edn ./deps.edn
COPY server/ ./server/
COPY resources/ ./resources/
COPY wasm/ ./wasm/
# SQLite store dir. A fly volume mounted at /app/data (fly.toml [mounts]) overlays
# this for persistence; db.clj creates + seeds books.db here on first run.
RUN mkdir -p /app/data

ENV PORT=8080 \
    CLJW_FS_ROOT=/app
# GOOGLE_CLIENT_ID is provided at runtime via `fly secrets set` (NOT baked in).

EXPOSE 8080
# bookshelf.server takes the port as its first arg; run-server binds 0.0.0.0.
CMD ["sh", "-c", "cljw -M:cljw -m bookshelf.server \"$PORT\""]
