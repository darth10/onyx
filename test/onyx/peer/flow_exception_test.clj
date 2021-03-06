(ns onyx.peer.flow-exception-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [midje.sweet :refer :all]
            [onyx.plugin.core-async :refer [take-segments!]]
            [taoensso.timbre :refer [info warn trace fatal] :as timbre]
            [onyx.test-helper :refer [load-config]]
            [onyx.api]))

(def id (java.util.UUID/randomUUID))

(def config (load-config))

(def env-config (assoc (:env-config config) :onyx/id id))

(def peer-config (assoc (:peer-config config) :onyx/id id))

(def env (onyx.api/start-env env-config))

(def peer-group (onyx.api/start-peer-group peer-config))

(def batch-size 10)

(def in-chan (chan 100))

(def out-chan (chan (sliding-buffer 100)))

(doseq [x (range 20)]
  (>!! in-chan {:n x}))

(>!! in-chan :done)
(close! in-chan)

(defn my-inc [{:keys [n] :as segment}]
  (cond (even? n)
        (throw (ex-info "Number was even" {:error :even :n n}))
        (= 5 n)
        (throw (ex-info "Number was 5" {:error :five :n n}))
        :else segment))

(def catalog
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :inc
    :onyx/fn :onyx.peer.flow-exception-test/my-inc
    :onyx/type :function
    :onyx/batch-size batch-size}

   {:onyx/name :out
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Writes segments to a core.async channel"}])

(def workflow
  [[:in :inc]
   [:inc :out]])

(defn inject-in-ch [event lifecycle]
  {:core.async/chan in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :onyx.peer.flow-exception-test/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.peer.flow-exception-test/out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(def flow-conditions
  [{:flow/from :inc
    :flow/to [:out]
    :flow/short-circuit? true
    :flow/thrown-exception? true
    :flow/predicate [:onyx.peer.flow-exception-test/even-exception?]
    :flow/post-transform :onyx.peer.flow-exception-test/transform-even}

   {:flow/from :inc
    :flow/to [:out]
    :flow/short-circuit? true
    :flow/thrown-exception? true
    :flow/predicate [:onyx.peer.flow-exception-test/five-exception?]
    :flow/post-transform :onyx.peer.flow-exception-test/transform-five}])

(defn even-exception? [event old e all-new]
  (= (:error (ex-data e)) :even))

(defn five-exception? [event old e all-new]
  (= (:error (ex-data e)) :five))

(defn transform-even [event segment e]
  {:error? true :value (:n segment)})

(defn transform-five [event segment e]
  {:error? true :value "abc"})

(def v-peers (onyx.api/start-peers 3 peer-group))

(onyx.api/submit-job
 peer-config
 {:catalog catalog :workflow workflow
  :flow-conditions flow-conditions :lifecycles lifecycles
  :task-scheduler :onyx.task-scheduler/balanced})

(def results (take-segments! out-chan))

(fact
 (into #{} results)
 =>
 #{{:error? true :value 0}
   {:n 1}
   {:error? true :value 2}
   {:n 3}
   {:error? true :value 4}
   {:error? true :value "abc"}
   {:error? true :value 6}
   {:n 7}
   {:error? true :value 8} 
   {:n 9}
   {:error? true :value 10}
   {:n 11}
   {:error? true :value 12}
   {:n 13}
   {:error? true :value 14}
   {:n 15}
   {:error? true :value 16}
   {:n 17}
   {:error? true :value 18}
   {:n 19}
   :done})

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)
