(ns bookshelf.subs
  (:require [io.factorhouse.rfx.core :as rfx]))

(defn register! []
  (rfx/reg-sub :user             (fn [db _] (:user db)))
  (rfx/reg-sub :google-client-id (fn [db _] (:google-client-id db)))
  (rfx/reg-sub :view             (fn [db _] (:view db)))
  (rfx/reg-sub :viewing          (fn [db _] (:viewing db)))
  (rfx/reg-sub :books            (fn [db _] (:books db)))
  (rfx/reg-sub :shelves          (fn [db _] (:shelves db)))
  (rfx/reg-sub :search           (fn [db _] (:search db)))
  (rfx/reg-sub :editing          (fn [db _] (:editing db)))
  (rfx/reg-sub :auth-error       (fn [db _] (:auth-error db))))
