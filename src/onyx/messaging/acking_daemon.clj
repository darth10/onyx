(ns ^:no-doc onyx.messaging.acking-daemon
    (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
              [com.stuartsierra.component :as component]
              [onyx.static.default-vals :refer [arg-or-default]]
              [onyx.types :refer [->Ack]]
              [taoensso.timbre :as timbre]))

(defn now []
  (System/currentTimeMillis))

(defn clear-messages-loop [state opts]
  (let [timeout (arg-or-default :onyx.messaging/ack-daemon-timeout opts)
        interval (arg-or-default :onyx.messaging/ack-daemon-clear-interval opts)]
    (loop []
      (try
        (Thread/sleep interval)
        (let [t (now)
              snapshot (:state @state)
              dead (map first (filter (fn [[k v]] (>= (- t (:timestamp v)) timeout)) snapshot))]
          (doseq [k dead]
            (swap! state update-in [:state] dissoc k)))
        (catch InterruptedException e
          (throw e))
        (catch Throwable e
          (timbre/fatal e)))
      (recur))))

(defrecord AckState [state completed?])

(defn ack-segment [ack-state completion-ch message-id completion-id ack-val]
  (let [rets
        (swap!
          ack-state
          (fn [as]
            (let [state (:state as)
                  ack (get state message-id)] 
              (if ack
                (let [updated-ack-val (bit-xor ^long (:ack-val ack) ^long ack-val)]
                  (if (zero? updated-ack-val)
                    (->AckState (dissoc state message-id) true)
                    (->AckState (assoc state message-id (assoc ack :ack-val updated-ack-val)) false)))
                (if (zero? ^long ack-val) 
                  (->AckState state true)
                  (->AckState (assoc state message-id (->Ack nil completion-id ack-val (now))) false))))))]
    (when (:completed? rets) 
      (>!! completion-ch
           {:id message-id :peer-id completion-id}))))

(defn ack-segments-loop [ack-state acking-ch completion-ch]
  (loop []
    (when-let [ack (<!! acking-ch)]
      (ack-segment ack-state completion-ch
                   (:id ack) (:completion-id ack) (:ack-val ack))
      (recur)))
  (timbre/info "Stopped Ack Messages Loop"))

(defrecord AckingDaemon [opts ack-state acking-ch completion-ch timeout-ch]
  component/Lifecycle

  (start [component]
    (taoensso.timbre/info "Starting Acking Daemon")
    (let [completion-buffer-size (arg-or-default :onyx.messaging/completion-buffer-size opts)
          completion-ch (chan completion-buffer-size)
          acking-buffer-size (arg-or-default :onyx.messaging/completion-buffer-size opts)
          acking-ch (chan acking-buffer-size)
          state (atom (->AckState {} false))
          ack-segments-fut (future (ack-segments-loop state acking-ch completion-ch))
          timeout-fut (future (clear-messages-loop state opts))]
      (assoc component
             :ack-state state
             :completion-ch completion-ch
             :acking-ch acking-ch
             :ack-segments-fut ack-segments-fut
             :timeout-fut timeout-fut)))

  (stop [component]
    (taoensso.timbre/info "Stopping Acking Daemon")
    (close! (:completion-ch component))
    (close! (:acking-ch component))
    (future-cancel (:timeout-fut component)) 
    (assoc component :ack-state nil :completion-ch nil :timeout-fut nil :ack-segments-fut nil)))

(defn acking-daemon [config]
  (map->AckingDaemon {:opts config}))

(defn gen-message-id
  "Generates a unique ID for a message - acts as the root id."
  []
  (java.util.UUID/randomUUID))

(defn gen-ack-value
  "Generate a 64-bit value to bit-xor against the current ack-value."
  []
  (.nextLong (java.util.concurrent.ThreadLocalRandom/current)))
