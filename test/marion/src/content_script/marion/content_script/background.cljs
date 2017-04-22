(ns marion.content-script.background
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [marion.content-script.logging :refer [log info warn error debug-log]]
                   [devtools.toolbox :refer [envelope]])
  (:require [cljs.core.async :refer [<! chan]]
            [oops.core :refer [oget ocall oapply]]
            [chromex.protocols :refer [post-message!]]
            [chromex.ext.runtime :as runtime]
            [marion.content-script.page :as page]))

; -- message handlers -------------------------------------------------------------------------------------------------------

(defn relay-message-to-page! [message]
  (page/send-message! message))

; -- message dispatch -------------------------------------------------------------------------------------------------------

(defn process-message! [message]
  (let [type (oget message "?type")]
    (debug-log "process background page message" type (envelope message))
    (case type
      "feedback-from-extension" (relay-message-to-page! message)
      "feedback-from-devtools" (relay-message-to-page! message)
      "feedback-from-scenario" (relay-message-to-page! message)
      "notification" (relay-message-to-page! message)
      "reply" (relay-message-to-page! message)
      (warn "got unknown message from background page" type message))))

; -- message loop -----------------------------------------------------------------------------------------------------------

(defn run-message-loop! [message-channel]
  (log "starting marion background page message loop...")
  (go-loop []
    (when-let [message (<! message-channel)]
      (process-message! message)
      (recur))
    (log "leaving marion background page mesage loop")))

; -- initialization ---------------------------------------------------------------------------------------------------------

(defn connect! []
  (let [background-port (runtime/connect)]                                                                                    ; connects to marion's background page
    (page/install! background-port)
    (run-message-loop! background-port)))
