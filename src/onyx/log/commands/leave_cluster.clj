(ns onyx.log.leave-cluster
  (:require [clojure.core.async :refer [chan go >! <! close!]]
            [clojure.set :refer [union difference map-invert]]
            [clojure.data :refer [diff]]
            [onyx.extensions :as extensions]))

(defmethod extensions/apply-log-entry :leave-cluster
  [{:keys [args]} replica]
  (let [{:keys [id]} args
        observer (get (map-invert (:pairs replica)) id)
        transitive (get (:pairs replica) id)
        pair (if (= observer transitive) {} {observer transitive})
        prep-observer (get (map-invert (:prepared replica)) id)
        accep-observer (get (map-invert (:accepted replica)) id)]
    (-> replica
        (update-in [:peers] (partial remove #(= % id)))
        (update-in [:prepared] dissoc id)
        (update-in [:prepared] dissoc prep-observer)
        (update-in [:accepted] dissoc id)
        (update-in [:accepted] dissoc accep-observer)
        (update-in [:pairs] merge pair)
        (update-in [:pairs] dissoc id)
        (update-in [:pairs] #(if-not (seq pair) (dissoc % observer) %)))))

(defmethod extensions/replica-diff :leave-cluster
  [{:keys [args]} old new]
  (let [observer (get (map-invert (:pairs old)) (:id args))
        subject (get (:pairs old) (:id args))]
    {:died (:id args)
     :updated-watch {:observer observer
                     :subject subject}}))

(defmethod extensions/reactions :leave-cluster
  [{:keys [args]} old new diff peer-args]
  (when (or (= (:id peer-args) (get (:prepared old) (:id args)))
            (= (:id peer-args) (get (:accepted old) (:id args))))
    [{:fn :abort-join-cluster :args {:id (:id peer-args)}}]))

(defmethod extensions/fire-side-effects! :leave-cluster
  [{:keys [args]} old new {:keys [updated-watch]} state]
  (when (and (= (:id state) (:observer updated-watch))
             (not= (:observer updated-watch) (:subject updated-watch)))
    (let [ch (chan 1)]
      (extensions/on-delete (:log state) (:subject updated-watch) ch)
      (go (when (<! ch)
            (extensions/write-log-entry
             (:log state)
             {:fn :leave-cluster :args {:id (:subject updated-watch)}}))
          (close! ch))
      (close! (or (:watch-ch state) (chan)))
      (assoc state :watch-ch ch))))
