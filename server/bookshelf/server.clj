(ns bookshelf.server
  "Bookshelf HTTP server on native ClojureWasm (cljw.http.server). Serves the
  compiled ClojureScript SPA + an EDN API. Auth is Google OIDC (bookshelf.auth):
  no passwords. Persistence is bookshelf.db (SQLite-via-wasm); book cover colours
  come from a Rust wasm module (bookshelf.cover). Wire format is EDN — both ends
  are Clojure(Script)."
  (:require [bookshelf.db :as db]
            [bookshelf.cover :as cover]
            [bookshelf.auth :as auth]
            [clojure.string :as str]
            [cljw.http.server :as http]))

;; Paths are relative to cwd (launch from the repo root, or via ./run_local.sh).
;; cljw.fs / clojure.java.io are available if richer path ops are wanted.
(def ^:private public-dir "resources/public")
(def ^:private port-default 8090)

;; ---- sessions (token -> google sub) ---------------------------------------

(def ^:private sessions (atom {}))

(defn- new-token []
  (apply str (repeatedly 24 #(rand-nth "abcdef0123456789"))))

(defn- cookie-token [req]
  (when-let [c (get-in req [:headers "cookie"])]
    (some (fn [kv]
            (let [[k v] (str/split (str/trim kv) #"=" 2)]
              (when (= k "bsid") v)))
          (str/split c #";"))))

(defn- current-sub [req] (get @sessions (cookie-token req)))

;; ---- responses ------------------------------------------------------------

(defn- edn [status data & [headers]]
  {:status status
   :headers (merge {"content-type" "application/edn; charset=utf-8"
                    "cache-control" "no-store"} headers)
   :body (pr-str data)})

(defn- body-edn [req]
  (try (read-string (or (:body req) "")) (catch Throwable _ nil)))

(defn- with-cover [book]
  (merge book (cover/colors (:title book))))

;; ---- static (SPA) ---------------------------------------------------------

(def ^:private ctype
  {"html" "text/html; charset=utf-8" "js" "text/javascript; charset=utf-8"
   "css" "text/css; charset=utf-8" "edn" "application/edn"})

(defn- ext [p] (let [i (str/last-index-of p ".")] (when i (subs p (inc i)))))

(defn- serve-static [uri]
  (let [rel (if (= uri "/") "index.html" (subs uri 1))
        path (str public-dir "/" rel)
        content (try (slurp path) (catch Throwable _ nil))]
    (if content
      {:status 200 :headers {"content-type" (get ctype (ext rel) "text/plain")
                             "cache-control" "no-store"} :body content}
      {:status 200 :headers {"content-type" "text/html; charset=utf-8"}
       :body (slurp (str public-dir "/index.html"))})))

;; ---- routing --------------------------------------------------------------

(defn- path-id [uri prefix]
  (let [tail (subs uri (count prefix))]
    (try (Integer/parseInt (first (str/split tail #"/"))) (catch Throwable _ nil))))

(defn- query-param [req k]
  (when-let [qs (:query-string req)]
    (some (fn [kv] (let [[a b] (str/split kv #"=" 2)] (when (= a k) b)))
          (str/split qs #"&"))))

(defn handler [req]
  (let [uri (:uri req) m (:request-method req)
        sub (current-sub req)]
    (cond
      ;; --- config (frontend reads the Google client id for GIS) ---
      (= uri "/api/config")
      (edn 200 {:google-client-id (auth/client-id)})

      ;; --- Google OIDC sign-in ---
      (and (= uri "/api/auth/google") (= m :post))
      (let [{:keys [id-token]} (body-edn req)
            res (auth/verify id-token)]
        (if (:error res)
          (edn 401 res)
          (let [user (db/upsert-user! res)
                t (new-token)]
            (swap! sessions assoc t (:sub user))
            (edn 200 {:user user}
                 {"set-cookie" (str "bsid=" t "; Path=/; SameSite=Lax")}))))

      (and (= uri "/api/logout") (= m :post))
      (do (when-let [t (cookie-token req)] (swap! sessions dissoc t))
          (edn 200 {:ok true}))

      (= uri "/api/me")
      (edn 200 {:user (when sub (db/user-by-sub sub))})

      ;; --- shelves & books (read) ---
      (= uri "/api/shelves") (edn 200 {:shelves (db/users)})

      (and (= uri "/api/books") (= m :get))
      (let [owner (query-param req "owner")
            books (if owner (db/books-of owner) (db/all-books))]
        (edn 200 {:books (mapv with-cover books)}))

      (= uri "/api/search")
      (edn 200 {:books (mapv with-cover (db/search (query-param req "q")))})

      ;; --- books (write, sign-in required) ---
      (and (= uri "/api/books") (= m :post))
      (if sub (edn 200 (with-cover (db/add-book! sub (body-edn req))))
          (edn 401 {:error "sign-in required"}))

      (and (str/starts-with? uri "/api/books/") (str/ends-with? uri "/favorite") (= m :post))
      (if sub (edn 200 (with-cover (db/toggle-favorite! sub (path-id uri "/api/books/"))))
          (edn 401 {:error "sign-in required"}))

      (and (str/starts-with? uri "/api/books/") (str/ends-with? uri "/copy") (= m :post))
      (if sub (edn 200 (with-cover (db/copy-book! sub (path-id uri "/api/books/"))))
          (edn 401 {:error "sign-in required"}))

      (and (str/starts-with? uri "/api/books/") (= m :put))
      (if sub (edn 200 (with-cover (db/update-book! sub (path-id uri "/api/books/") (body-edn req))))
          (edn 401 {:error "sign-in required"}))

      (and (str/starts-with? uri "/api/books/") (= m :delete))
      (if sub (edn 200 {:ok (boolean (db/delete-book! sub (path-id uri "/api/books/")))})
          (edn 401 {:error "sign-in required"}))

      (str/starts-with? uri "/api") (edn 404 {:error "not found"})
      :else (serve-static uri))))

(defn -main [& args]
  (db/init!)
  (let [port (if (seq args) (Integer/parseInt (first args)) port-default)]
    (println (str "Bookshelf on ClojureWasm — http://localhost:" port))
    (println (str "  auth: Google OIDC  (GOOGLE_CLIENT_ID "
                  (if (str/blank? (auth/client-id)) "NOT set — set it before sign-in)" "set)")))
    (http/run-server handler {:port port})))
