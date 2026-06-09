/* Minimal SQLite runner for ClojureWasm's (wasm/run …).
 * Usage (via wasm/run): argv[1] = db file path (under a preopened dir); SQL on stdin.
 * Emits query results as EDN to stdout — a vector of maps, one per row:
 *   [{:id "1" :title "SICP" :author "Abelson"} ...]
 * cljw reads EDN natively (read-string), so no JSON layer is needed. All column
 * values are emitted as EDN strings (NULL -> nil); the Clojure side coerces ints.
 *
 * Persistence model: SQLite's unix file VFS does not work cleanly on wasi-libc
 * (xRead -> ENOTSUP), but plain C fread/fwrite and an in-memory db both do. So
 * we load the db FILE into an in-memory database via sqlite3_deserialize, run the
 * SQL, and (only if it mutated) sqlite3_serialize back to the file. Real SQLite,
 * real file persistence, no file-VFS. Built with `zig cc --target=wasm32-wasi`. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include "sqlite3.h"

static void put_edn_string(const char *s) {
    putchar('"');
    for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
        switch (*p) {
            case '"':  fputs("\\\"", stdout); break;
            case '\\': fputs("\\\\", stdout); break;
            case '\n': fputs("\\n", stdout);  break;
            case '\r': fputs("\\r", stdout);  break;
            case '\t': fputs("\\t", stdout);  break;
            default:   putchar(*p);
        }
    }
    putchar('"');
}

static int row_cb(void *unused, int ncol, char **vals, char **names) {
    (void)unused;
    fputs("{", stdout);
    for (int i = 0; i < ncol; i++) {
        if (i) putchar(' ');
        printf(":%s ", names[i]);
        if (vals[i]) put_edn_string(vals[i]);
        else fputs("nil", stdout);
    }
    fputs("}\n", stdout);
    return 0;
}

static char *read_stdin(void) {
    size_t cap = 4096, len = 0;
    char *buf = malloc(cap);
    if (!buf) return NULL;
    int c;
    while ((c = getchar()) != EOF) {
        if (len + 1 >= cap) { cap *= 2; buf = realloc(buf, cap); if (!buf) return NULL; }
        buf[len++] = (char)c;
    }
    buf[len] = '\0';
    return buf;
}

/* Read the whole db file into a malloc'd buffer; *len set; NULL if absent/empty.
 * Reads in a growing loop until EOF — wasi-libc's fseek(SEEK_END)/ftell does not
 * reliably report file size, so size-then-read would read zero bytes. */
static unsigned char *read_file(const char *path, sqlite3_int64 *len) {
    *len = 0;
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    size_t cap = 16384, n = 0;
    unsigned char *buf = sqlite3_malloc64(cap);
    if (!buf) { fclose(f); return NULL; }
    for (;;) {
        if (n == cap) {
            cap *= 2;
            unsigned char *nb = sqlite3_realloc64(buf, cap);
            if (!nb) { sqlite3_free(buf); fclose(f); return NULL; }
            buf = nb;
        }
        size_t got = fread(buf + n, 1, cap - n, f);
        n += got;
        if (got == 0) break; /* EOF or error */
    }
    fclose(f);
    if (n == 0) { sqlite3_free(buf); return NULL; }
    *len = (sqlite3_int64)n;
    return buf;
}

static int write_file(const char *path, const unsigned char *buf, sqlite3_int64 len) {
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    size_t put = fwrite(buf, 1, (size_t)len, f);
    fclose(f);
    return put == (size_t)len ? 0 : -1;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: cljw_sqlite <db> ; SQL on stdin\n"); return 2; }
    const char *path = argv[1];

    sqlite3 *db;
    if (sqlite3_open(":memory:", &db) != SQLITE_OK) {
        fprintf(stderr, "open :memory: failed: %s\n", sqlite3_errmsg(db));
        return 1;
    }

    /* Load existing db bytes (if any) into the in-memory database. */
    sqlite3_int64 in_len = 0;
    unsigned char *in_buf = read_file(path, &in_len);
    if (in_buf && in_len > 0) {
        int rc = sqlite3_deserialize(db, "main", in_buf, in_len, in_len,
                                     SQLITE_DESERIALIZE_RESIZEABLE | SQLITE_DESERIALIZE_FREEONCLOSE);
        if (rc != SQLITE_OK) {
            fprintf(stderr, "deserialize failed: %s\n", sqlite3_errmsg(db));
            return 1;
        }
    }

    char *sql = read_stdin();
    if (!sql) { fprintf(stderr, "stdin read failed\n"); return 1; }

    fputs("[", stdout);
    char *err = NULL;
    int rc = sqlite3_exec(db, sql, row_cb, NULL, &err);
    fputs("]\n", stdout);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "sql error: %s\n", err ? err : "?");
        sqlite3_free(err);
        sqlite3_close(db);
        return 1;
    }

    /* Persist the db back to the file. Always serialize (total_changes does not
     * count DDL like CREATE TABLE); for a read-only query this rewrites identical
     * bytes, which is harmless for this single-process serial-access bookshelf. */
    {
        sqlite3_int64 out_len = 0;
        unsigned char *out_buf = sqlite3_serialize(db, "main", &out_len, 0);
        if (out_buf && out_len > 0) {
            int w = write_file(path, out_buf, out_len);
            sqlite3_free(out_buf);
            if (w != 0) { fprintf(stderr, "write db failed: %s\n", strerror(errno)); sqlite3_close(db); return 1; }
        }
    }

    sqlite3_close(db);
    free(sql);
    return 0;
}
