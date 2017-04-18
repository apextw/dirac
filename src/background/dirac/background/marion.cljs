(ns dirac.background.marion
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [dirac.background.logging :refer [log info warn error]]
                   [devtools.toolbox :refer [envelope]])
  (:require [cljs.core.async :refer [<! chan put!]]
            [oops.core :refer [oget ocall oapply]]
            [chromex.protocols :refer [post-message! get-sender get-name]]
            [dirac.background.state :as state]
            [cljs.reader :as reader]
            [dirac.background.helpers :as helpers]
            [dirac.options.model :as options]
            [dirac.utils :as utils]))

(declare post-feedback-event!)

; -- marion event handlers --------------------------------------------------------------------------------------------------

(defn set-options! [message-id message]
  (go
    (let [options (:options message)]
      (post-feedback-event! (str "set extension options:" (pr-str options)))
      (options/set-options! options)
      (state/post-reply! message-id))))

(defn get-options! [message-id _message]
  (go
    (state/post-reply! message-id (options/get-options))))

(defn reset-options! [message-id message]
  (go
    (let [options (:options message)]
      (post-feedback-event! (str "reset extension options:" (pr-str options)))
      (options/reset-options! options)
      (state/post-reply! message-id))))

(defn reset-state! [message-id _message]
  (go
    (post-feedback-event! (str "reset extension state"))
    (options/reset-to-defaults!)
    (state/reset-devtools-id-counter!)
    (state/post-reply! message-id)))

(defn fire-synthetic-chrome-event! [context message-id message]
  (go
    (let [handler-fn (:chrome-event-handler context)
          _ (assert (fn? handler-fn))
          chrome-event (:chrome-event message)
          reply (<! (handler-fn chrome-event))]
      (state/post-reply! message-id reply))))

(defn automate-dirac-frontend! [message-id message]
  (go
    (let [{:keys [action]} message
          devtools-id (utils/parse-int (:devtools-id message))]
      (log "automate-dirac-frontend!" (str "#" devtools-id) action (envelope message))
      (if (state/get-devtools-descriptor devtools-id)
        (let [reply (<! (helpers/automate-devtools! devtools-id action))]
          (state/post-raw-reply! message-id reply))
        (do
          (warn "dirac automation request for missing connection" (str "#" devtools-id) message
                "existing connections:" (state/get-devtools-descriptors))
          (state/post-reply! message-id ::missing-connection))))))

(defn tear-down! [message-id _message]
  (go
    ; we want to close all tabs/windows opened (owned) by our extension
    ; chrome driver does not have access to those windows and fails to switch back to its own tab
    ; https://bugs.chromium.org/p/chromium/issues/detail?id=355075
    (<! (helpers/close-all-extension-tabs!))
    (state/post-reply! message-id)))

; -- marion event loop ------------------------------------------------------------------------------------------------------

(defn register-marion! [marion-port]
  (log "marion connected" (envelope (get-sender marion-port)))
  (if (state/get-marion-port)
    (warn "overwriting previous marion port!"))
  (state/set-marion-port! marion-port))

(defn unregister-marion! []
  (if-let [port (state/get-marion-port)]
    (do
      (log "marion disconnected" (envelope (get-sender port)))
      (state/set-marion-port! nil))
    (warn "unregister-marion! called when no previous marion port!")))

(defn process-marion-message! [context data]
  (let [message-id (oget data "id")
        payload (oget data "payload")
        message (reader/read-string payload)
        command (:command message)]
    (log "process-marion-message" message-id command (envelope message))
    (case command
      :get-options (get-options! message-id message)
      :reset-options (reset-options! message-id message)
      :set-options (set-options! message-id message)
      :reset-state (reset-state! message-id message)
      :fire-synthetic-chrome-event (fire-synthetic-chrome-event! context message-id message)
      :automate-dirac-frontend (automate-dirac-frontend! message-id message)
      :tear-down (tear-down! message-id message))))

(defn run-marion-message-loop! [context marion-port]
  (go-loop []
    (when-let [data (<! marion-port)]
      (<! (process-marion-message! context data))
      (recur))
    (unregister-marion!)))

; -- marion client connection handling --------------------------------------------------------------------------------------

(defn handle-marion-client-connection! [context marion-port]
  (register-marion! marion-port)
  (run-marion-message-loop! context marion-port))

(defn post-feedback-event! [& args]
  (apply state/post-feedback! args))
