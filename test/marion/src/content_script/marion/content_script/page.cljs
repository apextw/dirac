(ns marion.content-script.page
  (:require-macros [marion.content-script.logging :refer [log info warn error debug-log]]
                   [devtools.toolbox :refer [envelope]])
  (:require [cljs.core.async :refer [<! chan go-loop]]
            [oops.core :refer [oget ocall oapply]]
            [chromex.protocols :refer [post-message!]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]))

; this code is responsible for communication between content script and hosting page
; see https://developer.chrome.com/extensions/content_scripts#host-page-communication

; -- send messages to page --------------------------------------------------------------------------------------------------

(defn send-message! [message]
  (.postMessage js/window message "*"))

; -- handle incoming messages from page -------------------------------------------------------------------------------------

(defn handle-marion-message! [port message]
  (debug-log "received page message, posting it to marion's background page" (envelope message))
  (post-message! port message))

(defn marion-message? [message]
  (let [type (oget message "type")]
    (and (string? type) (re-matches #"^marion-.*" type))))

; forward all marion-* messages to marion's background page
(defn process-page-message [port dom-event]
  {:pre [port dom-event]}
  (if-let [message (oget dom-event "?data")]
    (if (marion-message? message)
      (handle-marion-message! port message))))

; -- installation -----------------------------------------------------------------------------------------------------------

(defn install! [port]
  (.addEventListener js/window "message" (partial process-page-message port)))
