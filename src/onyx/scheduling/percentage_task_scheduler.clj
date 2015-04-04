(ns onyx.scheduling.percentage-task-scheduler
  (:require [onyx.scheduling.common-task-scheduler :as cts]
            [onyx.log.commands.common :as common]))

(defn highest-pct-task [replica job tasks]
  (->> tasks
       (sort-by #(get-in replica [:task-percentages job %]))
       (reverse)
       (first)))

(defn sort-tasks-by-pct [replica job tasks]
  (let [indexed
        (map-indexed
         (fn [k t]
           {:position k :task t :pct (get-in replica [:task-percentages job t])})
         (reverse tasks))]
    (reverse (sort-by (juxt :pct :position) indexed))))

(defn min-task-allocations [replica job tasks n-peers]
  (mapv
   (fn [task]
     (let [n (int (Math/floor (* (* 0.01 (:pct task)) n-peers)))]
       (assoc task :allocation n)))
   tasks))

(defn percentage-balanced-taskload [replica job candidate-tasks n-peers]
  (let [sorted-tasks (sort-tasks-by-pct replica job candidate-tasks)
        init-allocations (min-task-allocations replica job sorted-tasks n-peers)
        init-usage (apply + (map :allocation init-allocations))
        left-over-peers (- n-peers init-usage)
        with-leftovers (update-in init-allocations [0 :allocation] + left-over-peers)]
    (into {} (map (fn [t] {(:task t) t}) with-leftovers))))

(defn task-needing-pct-peers [replica job tasks peer]
  (let [allocations (get-in replica [:allocations job])
        total-allocated (count (into #{} (conj (apply concat (vals allocations)) peer)))
        balanced (percentage-balanced-taskload replica job tasks total-allocated)
        sorted-tasks (reverse (sort-by (juxt :pct :position) (vals balanced)))]
    (reduce
     (fn [default t]
       (let [pct (:pct (get balanced (:task t)))
             allocated (get allocations (:task t))
             required (int (Math/floor (* total-allocated (* 0.01 pct))))]
         (if (< (count allocated) required)
           (reduced (:task t))
           default)))
     (:task (first sorted-tasks))
     sorted-tasks)))

(defmethod cts/select-task :onyx.task-scheduler/percentage
  [replica job peer-id]
  (let [candidates (->> (get-in replica [:tasks job])
                        (cts/incomplete-tasks replica job)
                        (cts/active-tasks-only replica))]
    (or (task-needing-pct-peers replica job candidates peer-id)
        (highest-pct-task replica job candidates))))

(defmethod cts/drop-peers :onyx.task-scheduler/percentage
  [replica job n]
  (let [tasks (keys (get-in replica [:allocations job]))
        balanced (percentage-balanced-taskload replica job tasks n)]
    (mapcat
     (fn [[task {:keys [allocation]}]]
       (drop-last allocation (get-in replica [:allocations job task])))
     balanced)))

(defmethod cts/reallocate-from-task? :onyx.task-scheduler/percentage
  [scheduler old new job state]
  (let [allocation (common/peer->allocated-job (:allocations new) (:id state))]
    (when (= (:job allocation) job)
      (let [candidate-tasks (keys (get-in new [:allocations job]))
            n-peers (count (apply concat (vals (get-in new [:allocations job]))))
            balanced (percentage-balanced-taskload new job candidate-tasks n-peers)
            required (:allocation (get balanced (:task allocation)))
            actual (count (get-in new [:allocations (:job allocation) (:task allocation)]))]
        (when (> actual required)
          (let [n (- actual required)
                peers-to-drop (cts/drop-peers new (:job allocation) n)]
            (when (some #{(:id state)} (into #{} peers-to-drop))
              true)))))))

(defmethod cts/task-claim-n-peers :onyx.task-scheduler/percentage
  [replica job n]
  ;; We can reuse the Balanced task scheduler algorithm as is.
  (cts/task-claim-n-peers
   (assoc-in replica [:task-schedulers job] :onyx.task-scheduler/balanced)
   job n))

(defn reuse-spare-peers [replica job tasks spare-peers]
  (loop [[head & tail :as task-seq] (get-in replica [:tasks job])
         results tasks
         capacity spare-peers]
    (let [tail (vec tail)]
      (cond (or (<= capacity 0) (not (seq task-seq)))
            results
            (< (get results head) (or (get-in replica [:task-saturation job head] Double/POSITIVE_INFINITY)))
            (recur (conj tail head) (update-in results [head] inc) (dec capacity))
            :else
            (recur tail results capacity)))))

(defmethod cts/task-distribute-peer-count :onyx.task-scheduler/percentage
  [replica job n]
  (let [tasks (get-in replica [:tasks job])
        t (count tasks)
        min-peers (int (/ n t))
        r (rem n t)
        max-peers (inc min-peers)
        init
        (reduce
         (fn [all [task k]]
           (assoc all task (min (get-in replica [:task-saturation job task] Double/POSITIVE_INFINITY)
                                (get-in replica [:task-percentages job task])
                                (if (< k r) max-peers min-peers))))
         {}
         (map vector tasks (range)))
        spare-peers (- n (apply + (vals init)))]
    (reuse-spare-peers replica job init spare-peers)))
