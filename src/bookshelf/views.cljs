(ns bookshelf.views
  (:require [io.factorhouse.hsx.core :as hsx]
            [io.factorhouse.rfx.core :as rfx]
            [clojure.string :as str]
            [bookshelf.icons :as icons]
            ["react" :as react]
            ["@mantine/core" :as mantine :refer [Box Group Stack Button ActionIcon
                                                 Text Title Badge Tooltip Paper
                                                 TextInput Textarea SimpleGrid Card
                                                 Modal Select Container Avatar]]))

(def ^js AppShell (.-AppShell mantine))

(defn- icon [c props] (hsx/create-element [:> c props]))

;; ---------------------------------------------------------------- login -----

;; Renders Google Identity Services' "Sign in with Google" button into a ref div.
;; The GSI client script (loaded in index.html) may arrive after first render, so
;; we retry until window.google is present, then initialize with our client id.
(defn- gis-button [client-id dispatch]
  (let [ref (react/useRef nil)]
    (react/useEffect
     (fn []
       (letfn [(render []
                 (if (and (.-current ref)
                          (exists? js/window.google)
                          (.. js/window -google -accounts))
                   (let [g (.. js/window -google -accounts -id)]
                     (.initialize g #js {:client_id client-id
                                         :callback (fn [resp]
                                                     (dispatch [:auth/google (.-credential resp)]))})
                     (.renderButton g (.-current ref)
                                    #js {:theme "filled_blue" :size "large" :width 280}))
                   (js/setTimeout render 200)))]
         (render))
       js/undefined)
     #js [client-id])
    [:div {:ref ref}]))

(defn login-view []
  (let [dispatch (rfx/use-dispatch)
        cid      (rfx/use-sub [:google-client-id])
        err      (rfx/use-sub [:auth-error])]
    [:> Box {:style {:minHeight "100vh" :display "flex" :alignItems "center" :justifyContent "center"}}
     [:> Paper {:withBorder true :shadow "md" :radius "md" :p "xl" :style {:width 380}}
      [:> Group {:gap "xs" :mb "md" :align "center"}
       (icon icons/books {:size 28 :weight "duotone" :color "var(--mantine-color-indigo-6)"})
       [:> Title {:order 3} "Bookshelf on ClojureWasm"]]
      [:> Text {:size "xs" :c "dimmed" :mb "lg"}
       "A bookshelf running on native cljw, no JVM. Sign in with Google to keep your shelf."]
      (if (str/blank? cid)
        [:> Text {:c "red" :size "sm"}
         "The server has no GOOGLE_CLIENT_ID configured — set it and restart to enable sign-in."]
        [:> Box {:style {:display "flex" :justifyContent "center"}}
         [gis-button cid dispatch]])
      (when err [:> Text {:c "red" :size "sm" :mt "md"} err])]]))

;; ---------------------------------------------------------------- cards -----

(defn- cover [book size]
  [:> Box {:style {:height size :borderRadius 8 :background (or (:bg book) "#ced4da")
                   :display "flex" :alignItems "center" :justifyContent "center"
                   :color "white" :fontSize (/ size 2.4) :fontWeight 700
                   :boxShadow "inset 0 -24px 40px rgba(0,0,0,0.15)"}}
   (str/upper-case (subs (str (:title book) " ") 0 1))])

(defn- book-card [book]
  (let [dispatch (rfx/use-dispatch)
        user     (rfx/use-sub [:user])
        mine?    (= (:sub user) (:owner book))]
    [:> Card {:withBorder true :radius "md" :padding "sm"}
     [cover book 120]
     [:> Group {:justify "space-between" :mt "sm" :gap 4 :wrap "nowrap"}
      [:> Text {:fw 600 :size "sm" :lineClamp 1 :title (:title book)} (:title book)]
      [:> ActionIcon {:variant "subtle" :size "sm"
                      :color (if (:favorite book) "yellow" "gray")
                      :onClick #(when mine? (dispatch [:book/favorite (:id book)]))}
       (icon icons/star {:size 16 :weight (if (:favorite book) "fill" "regular")})]]
     [:> Text {:size "xs" :c "dimmed" :lineClamp 1} (:author book)]
     (when (seq (:labels book))
       [:> Group {:gap 4 :mt 4}
        (for [l (:labels book)] ^{:key l} [:> Badge {:size "xs" :variant "light"} l])])
     [:> Group {:gap 4 :mt "sm"}
      (if mine?
        [:<>
         [:> ActionIcon {:variant "light" :size "sm" :onClick #(dispatch [:book/edit book])}
          (icon icons/edit {:size 14})]
         [:> ActionIcon {:variant "light" :color "red" :size "sm" :onClick #(dispatch [:book/delete (:id book)])}
          (icon icons/trash {:size 14})]]
        (when user
          [:> Tooltip {:label "Copy to my shelf"}
           [:> ActionIcon {:variant "light" :size "sm" :onClick #(dispatch [:book/copy (:id book)])}
            (icon icons/copy {:size 14})]]))]]))

(defn- book-grid []
  (let [books (rfx/use-sub [:books])]
    (if (empty? books)
      [:> Text {:c "dimmed" :ta "center" :mt "xl"} "No books here yet."]
      [:> SimpleGrid {:cols #js {:base 2 :sm 3 :md 4 :lg 5} :spacing "md"}
       (for [b books] ^{:key (:id b)} [book-card b])])))

;; ----------------------------------------------------------------- editor ---

(defn- editor-modal []
  (let [dispatch (rfx/use-dispatch)
        editing  (rfx/use-sub [:editing])
        title    (react/useRef nil) author (react/useRef nil)
        desc     (react/useRef nil)  labels (react/useRef nil)]
    [:> Modal {:opened (boolean editing) :onClose #(dispatch [:book/cancel])
               :title (if (:id editing) "Edit book" "Add a book")}
     (when editing
       [:> Stack {:gap "sm"}
        [:> TextInput {:label "Title" :ref title :defaultValue (:title editing)}]
        [:> TextInput {:label "Author" :ref author :defaultValue (:author editing)}]
        [:> Textarea {:label "Description" :ref desc :autosize true :minRows 3 :defaultValue (:description editing)}]
        [:> TextInput {:label "Labels (comma-separated)" :ref labels
                       :defaultValue (str/join ", " (:labels editing))}]
        [:> Group {:justify "flex-end" :mt "xs"}
         [:> Button {:variant "default" :onClick #(dispatch [:book/cancel])} "Cancel"]
         [:> Button {:onClick #(dispatch [:book/save
                                          (assoc editing
                                                 :title (.. (.-current title) -value)
                                                 :author (.. (.-current author) -value)
                                                 :description (.. (.-current desc) -value)
                                                 :labels (->> (str/split (.. (.-current labels) -value) #",")
                                                              (map str/trim) (remove empty?) vec))])}
          "Save"]]])]))

;; ------------------------------------------------------------------ shell ---

(defn- header []
  (let [dispatch (rfx/use-dispatch)
        user     (rfx/use-sub [:user])
        view     (rfx/use-sub [:view])
        shelves  (rfx/use-sub [:shelves])
        viewing  (rfx/use-sub [:viewing])
        search   (react/useRef nil)
        others   (remove #(= (:sub %) (:sub user)) shelves)]
    [:> Group {:justify "space-between" :px "md" :h "100%" :wrap "nowrap"}
     [:> Group {:gap "xs" :wrap "nowrap"}
      (icon icons/books {:size 24 :weight "duotone" :color "var(--mantine-color-indigo-6)"})
      [:> Group {:gap 6 :align "baseline" :wrap "nowrap" :mr "sm"}
       [:> Title {:order 4} "Bookshelf"]
       [:> Text {:size "xs" :c "gray.7" :fw 500} "on ClojureWasm"]]
      [:> Button {:size "xs" :variant (if (= view :browse) "light" "subtle")
                  :leftSection (icon icons/house {:size 14}) :onClick #(dispatch [:view/browse])} "Browse"]
      [:> Button {:size "xs" :variant (if (= view :my-shelf) "light" "subtle")
                  :leftSection (icon icons/shelf {:size 14}) :onClick #(dispatch [:view/my-shelf])} "My shelf"]
      [:> Select {:size "xs" :placeholder "Other shelves" :w 170 :clearable true
                  :data (clj->js (mapv (fn [s] {:value (:sub s) :label (:name s)}) others))
                  :value (when (= view :shelf) viewing)
                  :onChange #(when % (dispatch [:view/shelf %]))}]]
     [:> Group {:gap "xs" :wrap "nowrap"}
      [:> TextInput {:size "xs" :placeholder "Search…" :ref search :w 170
                     :leftSection (icon icons/search {:size 14})
                     :onKeyDown #(when (= "Enter" (.-key %)) (dispatch [:search/run (.. % -target -value)]))}]
      [:> Button {:size "xs" :leftSection (icon icons/plus {:size 14}) :onClick #(dispatch [:book/new])} "Add"]
      [:> Group {:gap 6 :wrap "nowrap"}
       (when (seq (:picture user))
         [:> Avatar {:src (:picture user) :size 28 :radius "xl"}])
       [:> Text {:size "sm" :fw 500} (:name user)]
       [:> Tooltip {:label "Sign out"}
        [:> ActionIcon {:variant "subtle" :color "gray" :onClick #(dispatch [:auth/logout])}
         (icon icons/signout {:size 18})]]]]]))

(defn- shelf-title []
  (let [view (rfx/use-sub [:view]) viewing (rfx/use-sub [:viewing])
        search (rfx/use-sub [:search]) shelves (rfx/use-sub [:shelves])
        owner-name (some #(when (= (:sub %) viewing) (:name %)) shelves)]
    [:> Text {:c "dimmed" :size "sm" :mb "md"}
     (cond
       (seq search) (str "Search results for \"" search "\"")
       (= view :my-shelf) "Your shelf"
       (= view :shelf) (str (or owner-name "Someone") "'s shelf (read-only)")
       :else "All books")]))

(defn app-root []
  (let [user (rfx/use-sub [:user])]
    (if-not user
      [login-view]
      [:> AppShell {:header #js {:height 56} :padding "md"}
       [:> (.-Header AppShell) [header]]
       [:> (.-Main AppShell)
        [:> Container {:size "xl"}
         [shelf-title]
         [book-grid]
         [editor-modal]]]])))
