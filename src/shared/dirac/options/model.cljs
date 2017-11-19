(ns dirac.options.model
  (:require-macros [dirac.options.logging :refer [log warn error info]])
  (:require [cljs.core.async :refer [<! chan close! go go-loop]]
            [oops.core :refer [oget ocall oapply]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols :refer [get set]]
            [chromex.ext.storage :as storage]))

; we keep cached-options atom synced with values in storage
; we persist all options in local storage under "options" key as JSON string

(defonce default-options
  {:target-url                "http://localhost:9222"
   :open-as                   "panel"
   :enable-repl               true
   :enable-parinfer           true
   :enable-friendly-locals    true
   :enable-clustered-locals   true
   :inline-custom-formatters  true
   :welcome-message           true
   :clean-urls                true
   :beautify-function-names   true
   :link-actions              true
   :use-backend-supported-api true
   :use-backend-supported-css true
   :user-frontend-url-params  nil})

(defonce cached-options (atom nil))
(defonce chrome-event-channel (make-chrome-event-channel (chan)))

(defonce ^:dynamic *initialized* false)
(defonce ^:dynamic *auto-sync* true)

; -- public API -------------------------------------------------------------------------------------------------------------

(defn get-options []
  {:pre [*initialized*]}
  @cached-options)

(defn get-option [key]
  {:pre [*initialized*]}
  (key (get-options)))

(defn set-option! [key value]
  {:pre [*initialized*]}
  (log "set-option!" key value)
  (swap! cached-options assoc key value))                                                                                     ; will trigger on-cached-options-change!

(defn set-options! [options]
  {:pre [*initialized*]}
  (log "set-options!" (pr-str options))
  (swap! cached-options merge options))                                                                                       ; will trigger on-cached-options-change!

(defn reset-options! [options]
  {:pre [*initialized*]}
  (log "reset-options!" (pr-str options))
  (reset! cached-options options))                                                                                            ; will trigger on-cached-options-change!

(defn reset-to-defaults! []
  (reset-options! default-options))

; -- serialization ----------------------------------------------------------------------------------------------------------

(defn serialize-options [options]
  (let [json (clj->js options)]
    (.stringify js/JSON json)))

(defn unserialize-options [serialized-options]
  (assert (or (string? serialized-options) (object? serialized-options))
          (str "unexpected serialized-options of type " (type serialized-options) ": " (pr-str serialized-options)))
  (let [json (if (string? serialized-options)
               (.parse js/JSON serialized-options)
               serialized-options)]
    (js->clj json :keywordize-keys true)))

; -- read/write -------------------------------------------------------------------------------------------------------------

(defn write-options! [options]
  {:pre [*initialized*]}
  (info "write options:" options)
  (let [serialized-options (serialize-options options)
        local-storage (storage/get-local)]
    (set local-storage #js {"options" serialized-options})))                                                                  ; will trigger on-changed event and a call to reload-options!, which is fine

(defn on-cached-options-change! [new-options]
  (if *auto-sync*
    (write-options! new-options)))

(defn reset-cached-options-without-sync! [options]
  {:pre [*initialized*]}
  (binding [*auto-sync* false]
    (reset! cached-options options)))

(defn parse-options [serialized-options]
  (let [options (if (some? serialized-options)
                  (unserialize-options serialized-options))]
    (merge default-options options)))                                                                                         ; merge is important for upgrading options schema between versions

(defn read-options []
  (go
    (let [local-storage (storage/get-local)
          [[items] _error] (<! (get local-storage "options"))
          options (parse-options (oget items "?options"))]
      (info "read options:" options)
      options)))

(defn reload-options! [serialized-options]
  {:pre [*initialized*]}
  (let [options (parse-options serialized-options)]
    (info "reload options:" options)
    (reset-cached-options-without-sync! options)))

; -- events -----------------------------------------------------------------------------------------------------------------

(defn process-on-changed! [changes area-name]
  (go
    (when (= area-name "local")
      (reload-options! (oget changes "options.newValue")))))

(defn process-chrome-event [event]
  (log "got chrome event" event)
  (let [[event-id event-args] event]
    (case event-id
      ::storage/on-changed (apply process-on-changed! event-args)
      (go))))

(defn run-chrome-event-loop! [chrome-event-channel]
  (storage/tap-on-changed-events chrome-event-channel)
  (go-loop []
    (when-let [event (<! chrome-event-channel)]
      (<! (process-chrome-event event))
      (recur))
    (log "leaving event loop")))

; -- init/deinit ------------------------------------------------------------------------------------------------------------

(defn init! []
  {:pre [(not *initialized*)]}
  (log "init!")
  (go
    (let [options (<! (read-options))]
      (set! *initialized* true)
      (reset-cached-options-without-sync! options)
      (add-watch cached-options ::watch (fn [_ _ _ new-state]
                                          (on-cached-options-change! new-state)))
      (run-chrome-event-loop! chrome-event-channel)
      true)))

(defn deinit! []
  {:pre [*initialized*]}
  (log "deinit!")
  (remove-watch cached-options ::watch)
  (close! chrome-event-channel)
  (set! *initialized* false))
