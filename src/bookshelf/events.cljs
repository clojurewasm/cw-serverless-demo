(ns bookshelf.events
  (:require [io.factorhouse.rfx.core :as rfx]))

(defn register! []
  (rfx/reg-event-fx :app/init
    (fn [{:keys [db]} _]
      {:db db
       :http/get [{:path "/api/config"  :on-ok [:config/loaded]}
                  {:path "/api/me"      :on-ok [:auth/me]}
                  {:path "/api/shelves" :on-ok [:shelves/loaded]}
                  {:path "/api/books"   :on-ok [:books/loaded]}]}))

  ;; ---- auth (Google OIDC) ----
  (rfx/reg-event-db :config/loaded (fn [db [_ r]] (assoc db :google-client-id (:google-client-id r))))
  (rfx/reg-event-db :auth/me       (fn [db [_ r]] (assoc db :user (:user r))))

  ;; The GIS button hands us a credential (ID token JWT) in the browser; the
  ;; backend verifies it with Google and returns the user.
  (rfx/reg-event-fx :auth/google
    (fn [{:keys [db]} [_ credential]]
      {:db (assoc db :auth-error nil)
       :http/send {:method "POST" :path "/api/auth/google" :body {:id-token credential}
                   :on-ok [:auth/result] :on-err [:auth/error]}}))

  (rfx/reg-event-fx :auth/result
    (fn [{:keys [db]} [_ r]]
      (if (:user r)
        {:db (assoc db :user (:user r) :auth-error nil) :dispatch [:app/init]}
        {:db (assoc db :auth-error (or (:error r) "sign-in failed"))})))

  (rfx/reg-event-db :auth/error (fn [db [_ msg]] (assoc db :auth-error (str msg))))

  (rfx/reg-event-fx :auth/logout
    (fn [{:keys [db]} _]
      {:db (assoc db :user nil)
       :http/send {:method "POST" :path "/api/logout" :body nil :on-ok [:noop] :on-err [:noop]}}))
  (rfx/reg-event-db :noop (fn [db _] db))

  ;; ---- views & data ----
  (rfx/reg-event-fx :view/browse
    (fn [{:keys [db]} _]
      {:db (assoc db :view :browse :viewing nil :search "")
       :http/get {:path "/api/books" :on-ok [:books/loaded]}}))

  (rfx/reg-event-fx :view/my-shelf
    (fn [{:keys [db]} _]
      (let [sub (get-in db [:user :sub])]
        {:db (assoc db :view :my-shelf :viewing sub)
         :http/get {:path (str "/api/books?owner=" (js/encodeURIComponent sub)) :on-ok [:books/loaded]}})))

  (rfx/reg-event-fx :view/shelf
    (fn [{:keys [db]} [_ owner]]
      {:db (assoc db :view :shelf :viewing owner)
       :http/get {:path (str "/api/books?owner=" (js/encodeURIComponent owner)) :on-ok [:books/loaded]}}))

  (rfx/reg-event-db :books/loaded   (fn [db [_ r]] (assoc db :books (:books r))))
  (rfx/reg-event-db :shelves/loaded (fn [db [_ r]] (assoc db :shelves (:shelves r))))

  (rfx/reg-event-fx :search/run
    (fn [{:keys [db]} [_ q]]
      {:db (assoc db :search q :view :browse :viewing nil)
       :http/get {:path (str "/api/search?q=" (js/encodeURIComponent (or q ""))) :on-ok [:books/loaded]}}))

  ;; ---- book editor ----
  (rfx/reg-event-db :book/new    (fn [db _] (assoc db :editing {:title "" :author "" :description "" :labels []})))
  (rfx/reg-event-db :book/edit   (fn [db [_ book]] (assoc db :editing book)))
  (rfx/reg-event-db :book/cancel (fn [db _] (assoc db :editing nil)))

  (rfx/reg-event-fx :book/save
    (fn [{:keys [db]} [_ book]]
      (let [id (:id book)]
        {:db (assoc db :editing nil)
         :http/send {:method (if id "PUT" "POST")
                     :path (if id (str "/api/books/" id) "/api/books")
                     :body (select-keys book [:title :author :description :labels])
                     :on-ok [:books/changed] :on-err [:noop]}})))

  (rfx/reg-event-fx :book/delete
    (fn [{:keys [db]} [_ id]]
      {:db db :http/send {:method "DELETE" :path (str "/api/books/" id) :body nil
                          :on-ok [:books/changed] :on-err [:noop]}}))

  (rfx/reg-event-fx :book/favorite
    (fn [{:keys [db]} [_ id]]
      {:db db :http/send {:method "POST" :path (str "/api/books/" id "/favorite") :body nil
                          :on-ok [:books/changed] :on-err [:noop]}}))

  (rfx/reg-event-fx :book/copy
    (fn [{:keys [db]} [_ id]]
      {:db db :http/send {:method "POST" :path (str "/api/books/" id "/copy") :body nil
                          :on-ok [:book/copied] :on-err [:noop]}}))

  (rfx/reg-event-fx :book/copied (fn [{:keys [db]} _] {:db db :dispatch [:view/my-shelf]}))

  (rfx/reg-event-fx :books/changed
    (fn [{:keys [db]} _]
      {:db db
       :dispatch (case (:view db)
                   :my-shelf [:view/my-shelf]
                   :shelf    [:view/shelf (:viewing db)]
                   [:view/browse])})))
