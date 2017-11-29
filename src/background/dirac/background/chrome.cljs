(ns dirac.background.chrome
  (:require-macros [dirac.background.logging :refer [log info warn error]])
  (:require [cljs.core.async :refer [<! chan put! go go-loop]]
            [oops.core :refer [oget ocall oapply oset!]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols :refer [post-message! get-sender get-name]]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.windows :as windows]
            [chromex.ext.tabs :as tabs]
            [chromex.ext.browser-action :as browser-action]
            [chromex.ext.commands :as commands]
            [dirac.background.state :as state]
            [dirac.background.devtools :as devtools]
            [dirac.background.tools :as tools]
            [dirac.background.marion :as marion]))

(declare process-chrome-event!)

; -- event handlers ---------------------------------------------------------------------------------------------------------

(defn handle-command! [command & args]
  (log "handling command" command args)
  (marion/post-feedback-event! (str "handling command: " command))
  (case command
    "open-dirac-devtools" (apply tools/open-dirac-devtools-in-active-tab! args)
    "close-dirac-devtools" (apply tools/close-dirac-devtools! args)
    "focus-best-console-prompt" (apply tools/focus-best-console-prompt! args)
    (go
      (warn "received unrecognized command:" command))))

(defn on-tab-removed! [tab-id _remove-info]
  (go
    (if (devtools/frontend-connected? tab-id)
      (devtools/unregister! tab-id))))

(defn on-tab-updated! [tab-id _change-info _tab]
  (go
    (devtools/update-action-button! tab-id)))

(defn handle-external-client-connection! [client-port]
  (go
    (case (get-name client-port)
      "Dirac Marionettist" (marion/handle-marion-client-connection! {:chrome-event-handler process-chrome-event!} client-port)
      (warn "external connection attempt from unrecognized client" client-port))))

; -- main event loop --------------------------------------------------------------------------------------------------------

(defn process-chrome-event! [event]
  (log "dispatch chrome event" event)
  (let [[event-id event-args] event]
    (case event-id
      ::browser-action/on-clicked (apply tools/activate-or-open-dirac-devtools! event-args)
      ::commands/on-command (apply handle-command! event-args)
      ::tabs/on-removed (apply on-tab-removed! event-args)
      ::tabs/on-updated (apply on-tab-updated! event-args)
      ::runtime/on-connect-external (apply handle-external-client-connection! event-args)
      (go))))

(defn run-chrome-event-loop! [chrome-event-channel]
  (log "starting main event loop...")
  (go-loop []
    (when-let [event (<! chrome-event-channel)]
      (<! (process-chrome-event! event))
      (recur))
    (log "leaving main event loop")))

(defn start-chrome-event-loop! []
  (let [channel (make-chrome-event-channel (chan))]
    (state/set-chrome-event-channel! channel)
    (tabs/tap-all-events channel)
    (runtime/tap-all-events channel)
    (windows/tap-all-events channel)
    (browser-action/tap-on-clicked-events channel)
    (commands/tap-on-command-events channel)
    (run-chrome-event-loop! channel)))
