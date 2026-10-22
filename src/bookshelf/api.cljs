(ns bookshelf.api
  "EDN-over-HTTP client. The cljw backend speaks EDN (both ends are Clojure), so
  responses are read with cljs.reader and request bodies are pr-str'd. The session
  cookie rides along via credentials: \"include\"."
  (:require [cljs.reader :as reader]))

(def base
  (if (= js/window.location.port "3001") "http://localhost:8090/" ""))

(defn- url
  "Request URLs are RELATIVE to the page: the app may sit under a path prefix
  (e.g. /bookshelf/) behind a gateway that strips it, so \"/api/x\" is sent as
  \"api/x\" and the browser resolves it against the document's base URL."
  [path]
  (str base (if (= "/" (subs path 0 1)) (subs path 1) path)))

(defn- read-edn [s] (try (reader/read-string s) (catch :default _ nil)))

(defn get-edn [path on-ok on-err]
  (-> (js/fetch (url path) #js {:credentials "include" :cache "no-store"})
      (.then (fn [r] (.text r)))
      (.then (fn [t] (on-ok (read-edn t))))
      (.catch (fn [e] (on-err (str e))))))

(defn send-edn [method path body on-ok on-err]
  (-> (js/fetch (url path)
                #js {:method method
                     :credentials "include"
                     :headers #js {"content-type" "application/edn"}
                     :body (when body (pr-str body))})
      (.then (fn [r] (.text r)))
      (.then (fn [t] (on-ok (read-edn t))))
      (.catch (fn [e] (on-err (str e))))))
