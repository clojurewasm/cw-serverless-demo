(ns bookshelf.core
  (:require ["react-dom/client" :as rdc]
            ["@mantine/core" :refer [MantineProvider createTheme]]
            [io.factorhouse.hsx.core :as hsx]
            [io.factorhouse.rfx.core :as rfx]
            [bookshelf.api :as api]
            [bookshelf.events :as events]
            [bookshelf.subs :as subs]
            [bookshelf.state :as state]
            [bookshelf.views :as views]))

(defonce ^:private ctx (rfx/init {:initial-value state/empty-db}))
(defonce ^:private root (atom nil))

(def ^:private theme (createTheme #js {:primaryColor "indigo" :defaultRadius "md"}))

(defn- register-effects! []
  (rfx/reg-fx :http/get
    (fn [{:keys [dispatch]} specs]
      (doseq [{:keys [path on-ok]} (if (map? specs) [specs] specs)]
        (api/get-edn path
                     (fn [b] (dispatch (conj on-ok b)))
                     (fn [e] (js/console.error "GET" path e))))))
  (rfx/reg-fx :http/send
    (fn [{:keys [dispatch]} {:keys [method path body on-ok on-err]}]
      (api/send-edn method path body
                    (fn [r] (dispatch (conj on-ok r)))
                    (fn [e] (dispatch (conj on-err e)))))))

(defn- app []
  [:> rfx/RfxContextProvider #js {"value" ctx}
   [:> MantineProvider {:theme theme :defaultColorScheme "light"}
    [views/app-root]]])

(defn ^:dev/after-load mount []
  (when-not @root
    (reset! root (rdc/createRoot (.getElementById js/document "app"))))
  (.render ^js @root (hsx/create-element [app])))

(defn init []
  (events/register!)
  (subs/register!)
  (register-effects!)
  (mount)
  (rfx/dispatch ctx [:app/init]))
