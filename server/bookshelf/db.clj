(ns bookshelf.db
  "Bookshelf persistence backed by a REAL SQLite database — compiled from C to
  wasm32-wasi and driven via cljw's (wasm/run …). Each call runs sqlite3.wasm
  against data/books.db (mapped into the guest as /data/books.db via a preopened
  dir); the module loads the file, runs the SQL, persists, and prints result rows
  as EDN, which we read natively. A Clojure app on cljw doing real DB calls into a
  C library running as WebAssembly — no JVM, no JDBC, no Docker.

  Identity is Google OIDC (see bookshelf.auth): users are keyed by the Google
  `sub`; we store {sub, email, name, picture} — NO passwords. books.owner = sub."
  (:require [clojure.string :as str]))

(def ^:private wasm "wasm/sqlite3.wasm")
(def ^:private guest-db "/data/books.db")
(def ^:private preopen [["data" "/data"]])

;; ---- the wasm/run bridge --------------------------------------------------

(defn- run-sql [sql]
  (let [r (wasm/run wasm {:args ["sqlite3" guest-db] :dirs preopen :stdin sql})]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "sqlite error: " (:err r)) {:sql sql :err (:err r)})))
    r))

(defn- query [sql] (read-string (:out (run-sql sql))))
(defn- exec! [sql] (run-sql sql) nil)

(defn- q
  "Single-quote a SQL string literal, doubling embedded quotes."
  [s]
  (str "'" (str/replace (str s) "'" "''") "'"))

;; ---- schema + seed --------------------------------------------------------

(declare upsert-user! add-book! all-books)

(defn- seed-if-empty! []
  (when (empty? (all-books))
    ;; A sample shelf owned by a placeholder user so a freshly-signed-in visitor
    ;; has books to browse + copy. Nobody signs in as this user (auth is Google).
    (upsert-user! {:sub "seed-demo" :email "demo@clojurewasm.local"
                   :name "ClojureWasm Demo" :picture ""})
    (doseq [b [{:title "Structure and Interpretation of Computer Programs"
                :author "Abelson & Sussman" :labels ["classic" "lisp"]}
               {:title "The Joy of Clojure" :author "Fogus & Houser" :labels ["clojure"]}
               {:title "Programming Clojure" :author "Halloway & Bedra" :labels ["clojure"]}
               {:title "The Little Schemer" :author "Friedman & Felleisen" :labels ["scheme" "classic"]}
               {:title "Crafting Interpreters" :author "Nystrom" :labels ["languages"]}
               {:title "Land of Lisp" :author "Barski" :labels ["lisp" "fun"]}]]
      (add-book! "seed-demo" b))))

(defn init! []
  (exec! (str "CREATE TABLE IF NOT EXISTS users("
              "sub TEXT PRIMARY KEY, email TEXT, name TEXT, picture TEXT);"
              "CREATE TABLE IF NOT EXISTS books("
              "id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT, title TEXT,"
              "author TEXT, description TEXT, labels TEXT, favorite INTEGER DEFAULT 0);"))
  (seed-if-empty!))

;; ---- users (Google identities) --------------------------------------------

(defn upsert-user! [{:keys [sub email name picture]}]
  (exec! (str "INSERT INTO users(sub,email,name,picture) VALUES("
              (q sub) "," (q email) "," (q name) "," (q (or picture "")) ")"
              " ON CONFLICT(sub) DO UPDATE SET email=" (q email)
              ",name=" (q name) ",picture=" (q (or picture "")) ";"))
  {:sub sub :email email :name name :picture picture})

(defn user-by-sub [sub]
  (first (query (str "SELECT sub,email,name,picture FROM users WHERE sub=" (q sub) ";"))))

(defn users
  "Shelf owners for the 'other shelves' picker: [{:sub :name :email} …]."
  []
  (query "SELECT sub,name,email FROM users ORDER BY name;"))

;; ---- books ----------------------------------------------------------------

(defn- ->book [row]
  {:id (parse-long (:id row))
   :owner (:owner row)
   :title (:title row)
   :author (:author row)
   :description (or (:description row) "")
   :labels (if (str/blank? (:labels row)) [] (str/split (:labels row) #","))
   :favorite (= "1" (:favorite row))})

(defn all-books []
  (mapv ->book (query "SELECT * FROM books ORDER BY id;")))

(defn books-of [owner]
  (mapv ->book (query (str "SELECT * FROM books WHERE owner=" (q owner) " ORDER BY id;"))))

(defn get-book [id]
  (some-> (first (query (str "SELECT * FROM books WHERE id=" (long id) ";"))) ->book))

(defn- labels->str [labels] (str/join "," (map str (or labels []))))

(defn add-book! [owner {:keys [title author description labels]}]
  (exec! (str "INSERT INTO books(owner,title,author,description,labels,favorite) VALUES("
              (q owner) "," (q (or title "Untitled")) "," (q (or author "")) ","
              (q (or description "")) "," (q (labels->str labels)) ",0);"))
  (->book (first (query (str "SELECT * FROM books WHERE owner=" (q owner)
                             " ORDER BY id DESC LIMIT 1;")))))

(defn- owned? [owner id]
  (= owner (:owner (get-book id))))

(defn update-book! [owner id {:keys [title author description labels]}]
  (when (owned? owner id)
    (exec! (str "UPDATE books SET title=" (q title) ",author=" (q author)
                ",description=" (q description) ",labels=" (q (labels->str labels))
                " WHERE id=" (long id) ";"))
    (get-book id)))

(defn delete-book! [owner id]
  (when (owned? owner id)
    (exec! (str "DELETE FROM books WHERE id=" (long id) ";"))
    true))

(defn toggle-favorite! [owner id]
  (when (owned? owner id)
    (exec! (str "UPDATE books SET favorite=1-favorite WHERE id=" (long id) ";"))
    (get-book id)))

(defn copy-book! [owner src-id]
  (when-let [src (get-book src-id)]
    (add-book! owner (select-keys src [:title :author :description :labels]))))

(defn search [qstr]
  (if (str/blank? qstr)
    (all-books)
    (let [like (str "%" (str/replace (str/lower-case qstr) "'" "''") "%")]
      (mapv ->book
            (query (str "SELECT * FROM books WHERE lower(title) LIKE '" like "'"
                        " OR lower(author) LIKE '" like "'"
                        " OR lower(labels) LIKE '" like "' ORDER BY id;"))))))
