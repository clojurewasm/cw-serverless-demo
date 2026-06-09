(ns bookshelf.cover
  "Book-cover colours computed by a Rust module compiled to WebAssembly, called
  over the cljw FFI with (wasm/call …). The title is hashed in Clojure to an
  integer; the Rust module turns it into a pleasant colour (golden-ratio hue +
  HSL→RGB), so an other-language asset does real work inside the app.")

(def ^:private mod (atom nil))

(defn- module []
  (or @mod (reset! mod (wasm/load "wasm/cover_color.wasm"))))

(defn- title-hash
  "Stable 31-bit positive hash of a title (overflow-free in the long range)."
  [s]
  (reduce (fn [h c] (bit-and (+ (* h 31) (int c)) 0x7fffffff))
          7 (seq (or s ""))))

(defn- hex [n] (format "#%06x" (bit-and n 0xffffff)))

(defn colors
  "Return {:bg \"#rrggbb\" :accent \"#rrggbb\"} for a book title."
  [title]
  (let [h (title-hash title)
        m (module)]
    {:bg     (hex (wasm/call m "color_from_hash" h))
     :accent (hex (wasm/call m "accent_from_hash" h))}))
