(ns dirac.automation.launcher
  (:require [cljs.core.async :refer [put! <! chan timeout alts! close! go go-loop]]
            [goog.string :as string]
            [oops.core :refer [oget oset! oset!+ ocall ocall+ oapply gset! gcall!]]
            [chromex.logging :refer-macros [log warn error info]]
            [dirac.settings :refer-macros [get-launch-task-key get-launch-task-message
                                           get-kill-task-key get-kill-task-message]]))

(defn register-task! [task-fn kill-fn]
  (log "registering task...")
  (gset! "!" (get-launch-task-key) task-fn)
  (gset! "!" (get-kill-task-key) kill-fn))

(defn kill-task! []
  (log "killing task... via" (get-kill-task-key))
  (gcall! (get-kill-task-key)))                                                                                               ; see go-task

(defn launch-task! []
  (log "launching task... via" (get-launch-task-key))
  (gcall! (get-launch-task-key)))                                                                                             ; see go-task

(defn launch-task-after-delay! [delay-ms]
  (log "scheduled task launch after " delay-ms "ms...")
  (go
    (if (pos? delay-ms)
      (<! (timeout delay-ms)))
    (launch-task!)))

(defn process-event! [event]
  (if-let [data (oget event "?data")]
    (let [type (oget data "?type")]
      (cond
        (= type (get-launch-task-message)) (launch-task-after-delay! (string/parseInt (oget data "?delay")))
        (= type (get-kill-task-message)) (kill-task!)))))

(defn init! []
  (.addEventListener js/window "message" process-event!))
