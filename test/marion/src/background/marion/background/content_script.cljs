(ns marion.background.content-script
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [marion.background.logging :refer [log info warn error]]
                   [devtools.toolbox :refer [envelope]])
  (:require [cljs.core.async :refer [<! chan timeout put! close! alts!]]
            [oops.core :refer [oget oset! ocall oapply]]
            [chromex.protocols :refer [post-message! get-sender]]
            [marion.background.helpers :as helpers]
            [marion.background.feedback :as feedback]
            [marion.background.notifications :as notifications]
            [marion.background.dirac :as dirac]
            [marion.background.clients :as clients]
            [goog.string :as gstring]
            [goog.string.format]
            [dirac.utils :as utils]))

; -- scenario ids -----------------------------------------------------------------------------------------------------------
; we want to provide stable mapping between tab-ids and scenario tabs
(defonce scenario-ids (atom {}))                                                                                              ; scenario-id -> tab-id
(defonce last-scenario-id (volatile! 0))

(defn get-next-scenario-id! []
  (str "scenario-tab#" (vswap! last-scenario-id inc)))

(defn reset-scenario-id! []
  (vreset! last-scenario-id 0))

(defn clear-scenario-ids! []
  (reset! scenario-ids {}))

(defn get-scenario-tab-id [scenario-id]
  {:pre  [(some? scenario-id)]
   :post [(some? %)]}
  (get @scenario-ids scenario-id))

(defn add-scenario-id! [scenario-id tab-id]
  {:pre [(some? scenario-id)
         (some? tab-id)
         (nil? (get @scenario-ids scenario-id))]}
  (swap! scenario-ids assoc scenario-id tab-id)
  (log "added scenario id" scenario-id "=>" tab-id))

(defn remove-scenario-id! [scenario-id]
  {:pre [(some? scenario-id)
         (some? (get-scenario-tab-id scenario-id))]}
  (swap! scenario-ids dissoc scenario-id)
  (log "removed scenario id" scenario-id))

; -- pending scenarios ------------------------------------------------------------------------------------------------------
; after opening a new tab with scenario URL, we wait for special signal from scenario page that it finished initialization
; and is ready for testing exercise
; in pending-scenarios we keep mappings from opened tab urls which have yet to be marked as "ready"

(defonce pending-scenarios (atom {}))                                                                                         ; scenario-url -> callback

(defn wait-for-scenario-ready! [scenario-url]
  (let [channel (chan)
        handler (fn [message]
                  (swap! pending-scenarios dissoc scenario-url)
                  (put! channel message))]
    (swap! pending-scenarios assoc scenario-url handler)
    channel))

; -- posting replies --------------------------------------------------------------------------------------------------------

(defn serialize-error [e]
  (pr-str (utils/make-error-struct e)))

(defn serialize-reply-data [data]
  (if (instance? js/Error data)
    (serialize-error data)
    (try
      (pr-str (utils/make-result-struct data))
      (catch :default e
        (serialize-error e)))))

(defn post-reply!
  ([message-id] (post-reply! message-id nil))
  ([message-id data]
   (let [message #js {:type "reply"
                      :id   message-id
                      :data (serialize-reply-data data)}]
     (log "broadcasting reply" message-id (envelope message))
     (feedback/broadcast-feedback! message))))

(defn reply-to-message! [message & [data]]
  (post-reply! (oget message "id") data))

; -- message handlers -------------------------------------------------------------------------------------------------------

(defn reset-state! [message]
  (go
    (reset-scenario-id!)
    (clear-scenario-ids!)
    (<! (reply-to-message! message))))

(defn subscribe-client-to-feedback! [message client]
  (go
    (feedback/subscribe-client! client)
    (<! (reply-to-message! message))))

(defn unsubscribe-client-from-feedback! [message client]
  (go
    (feedback/unsubscribe-client! client)
    (<! (reply-to-message! message))))

(defn subscribe-client-to-notifications! [message client]
  (go
    (notifications/subscribe-client! client)
    (<! (reply-to-message! message))))

(defn unsubscribe-client-from-notifications! [message client]
  (go
    (notifications/unsubscribe-client! client)
    (<! (reply-to-message! message))))

(defn open-scenario! [message]
  (go
    (let [scenario-url (oget message "url")                                                                                   ; something like http://localhost:9080/scenarios/normal.html
          scenario-id (get-next-scenario-id!)
          timeout-ms 3000
          timeout-str (gstring/format "%0.2f" (/ timeout-ms 1000.0))
          timeout-channel (timeout timeout-ms)
          ready-channel (wait-for-scenario-ready! scenario-url)
          tab-id (<! (helpers/create-scenario-with-url! scenario-url))]
      (let [[_ channel] (alts! [ready-channel timeout-channel])]
        (condp identical? channel
          ready-channel (do (add-scenario-id! scenario-id tab-id)
                            (<! (reply-to-message! message scenario-id)))
          timeout-channel (let [error-msg (str "Scenario " scenario-id " didn't get ready in time (" timeout-str "s), "
                                               "this is probably due to javascript errors during initialization.\n"
                                               "Inspect page '" scenario-url "'")]
                            (warn error-msg)
                            (<! (reply-to-message! message (str "error: " error-msg)))))))))

(defn close-scenario! [message]
  (go
    (let [scenario-id (oget message "scenario-id")
          tab-id (get-scenario-tab-id scenario-id)]
      (<! (helpers/close-tab-with-id! tab-id))
      (remove-scenario-id! scenario-id)
      (<! (reply-to-message! message)))))

(defn activate-scenario! [message]
  (go
    (let [scenario-id (oget message "scenario-id")
          tab-id (get-scenario-tab-id scenario-id)]
      (<! (helpers/activate-tab! tab-id))
      (<! (reply-to-message! message)))))

(defn scenario-ready! [message client]
  (go
    (let [sender (get-sender client)
          scenario-url (oget sender "url")]
      (log "scenario is ready" scenario-url)
      (if-let [callback (get @pending-scenarios scenario-url)]
        (callback message)
        (warn "expected " scenario-url " to be present in pending-scenarios")))
    (<! (reply-to-message! message))))

(defn switch-to-task-runner! [message]
  (go
    (when-let [tab-id (<! (helpers/find-runner-tab-id!))]
      (<! (helpers/activate-tab! tab-id))
      (<! (reply-to-message! message)))))

(defn focus-runner-window! [message]
  (go
    (when-let [tab-id (<! (helpers/find-runner-tab-id!))]
      (<! (helpers/focus-window-with-tab-id! tab-id))
      (<! (reply-to-message! message)))))

(defn reposition-runner-window! [message]
  (go
    (<! (helpers/reposition-runner-window!))
    (<! (reply-to-message! message))))

(defn close-all-tabs! [message]
  (go
    (<! (helpers/close-all-scenario-tabs!))
    (clear-scenario-ids!)
    (<! (reply-to-message! message))))

(defn handle-extension-command! [message]
  (dirac/post-message-to-dirac-extension! message))

(defn broadcast-feedback-from-scenario! [message]
  (go
    (<! (feedback/broadcast-feedback! (oget message "payload")))
    (<! (reply-to-message! message))))

(defn broadcast-notification! [message]
  (go
    (<! (notifications/broadcast-notification! (oget message "payload")))
    (<! (reply-to-message! message))))

; -- message dispatch -------------------------------------------------------------------------------------------------------

(defn process-message! [client message]
  (let [message-type (oget message "type")
        message-id (oget message "id")]
    (log "dispatch content script message" message-id message-type (envelope message))
    (case message-type
      "marion-reset-state" (reset-state! message)
      "marion-subscribe-feedback" (subscribe-client-to-feedback! message client)
      "marion-unsubscribe-feedback" (unsubscribe-client-from-feedback! message client)
      "marion-feedback-from-scenario" (broadcast-feedback-from-scenario! message)
      "marion-subscribe-notifications" (subscribe-client-to-notifications! message client)
      "marion-unsubscribe-notifications" (unsubscribe-client-from-notifications! message client)
      "marion-broadcast-notification" (broadcast-notification! message)
      "marion-open-scenario" (open-scenario! message)
      "marion-close-scenario" (close-scenario! message)
      "marion-activate-scenario" (activate-scenario! message)
      "marion-scenario-ready" (scenario-ready! message client)
      "marion-switch-to-runner-tab" (switch-to-task-runner! message)
      "marion-reposition-runner-window" (reposition-runner-window! message)
      "marion-focus-runner-window" (focus-runner-window! message)
      "marion-close-all-tabs" (close-all-tabs! message)
      "marion-extension-command" (handle-extension-command! message))))

; -- content script message loop --------------------------------------------------------------------------------------------

(defn run-message-loop! [client]
  (log "entering run-message-loop! of" (helpers/get-client-url client))
  (go-loop []
    (when-some [message (<! client)]
      (<! (process-message! client message))
      (recur))
    (log "leaving run-message-loop! of" (helpers/get-client-url client))))

; -- event handlers ---------------------------------------------------------------------------------------------------------

(defn handle-new-connection! [client]
  (go
    (clients/add-client! client)
    (<! (run-message-loop! client))
    (clients/remove-client! client)))
