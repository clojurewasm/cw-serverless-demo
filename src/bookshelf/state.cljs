(ns bookshelf.state)

(def empty-db
  {:user      nil        ; {:sub :name :email :picture} or nil (Google identity)
   :google-client-id nil ; from GET /api/config (for GIS init)
   :view      :browse    ; :browse | :my-shelf | :shelf
   :books     []         ; books for the current view (each enriched with :bg :accent)
   :shelves   []         ; [{:sub :name :email} …]
   :viewing   nil        ; owner sub whose shelf is shown in :shelf view
   :search    ""
   :editing   nil        ; book map being edited in the modal, or nil
   :auth-error nil})
