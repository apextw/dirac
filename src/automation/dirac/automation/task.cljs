(ns dirac.automation.task
  (:require [cljs.core.async :refer [put! <! chan timeout alts! close! go go-loop]]
            [oops.core :refer [oget oset! ocall oapply gset!]]
            [dirac.automation.logging :refer [log warn error info]]
            [dirac.settings :refer [get-signal-server-url
                                    get-transcript-streamer-server-url
                                    get-chrome-remote-debugging-port
                                    get-chrome-remote-debugging-host
                                    get-pending-replies-wait-timeout
                                    get-signal-client-task-result-delay
                                    get-signal-client-close-delay]]
            [dirac.lib.ws-client :as ws-client]
            [dirac.options.model :as options-model]
            [dirac.automation.transcript-host :as transcript-host]
            [dirac.automation.status-host :as status-host]
            [dirac.automation.feedback :as feedback]
            [dirac.automation.messages :as messages]
            [dirac.automation.launcher :as launcher]
            [dirac.automation.helpers :as helpers]
            [dirac.shared.utils :as utils]
            [dirac.automation.runner :as runner]))

(declare register-global-exception-handler!)

(defonce setup-done (volatile! false))
(defonce exit-code (volatile! nil))
(defonce failed-state? (volatile! false))

; -- cljs printing ----------------------------------------------------------------------------------------------------------

(defn init-cljs-printing! []
  (set! *print-newline* false)
  (set! *print-fn* (fn [& args]
                     (.apply (.-log js/console) js/console (into-array args))
                     (transcript-host/append-to-transcript! "stdout" (apply str args))))
  (set! *print-err-fn* (fn [& args]
                         (.apply (.-error js/console) js/console (into-array args))
                         (transcript-host/append-to-transcript! "stderr" (apply str args))))
  nil)

; -- chrome driver support --------------------------------------------------------------------------------------------------

(defn setup-debugging-port! []
  (let [debugging-host (or (helpers/get-document-url-param "debugging_host")
                           (get-chrome-remote-debugging-host)
                           "http://localhost")
        debugging-port (or (helpers/get-document-url-param "debugging_port")
                           (get-chrome-remote-debugging-port)
                           "9222")
        target-url (str debugging-host ":" debugging-port)]
    (when-not (= target-url (:target-url options-model/default-options))
      (info (str "Setting Dirac Extension :target-url option to " target-url))
      (messages/set-options! {:target-url target-url}))))

; -- helpers ----------------------------------------------------------------------------------------------------------------

(defn reset-browser-state! []
  (go
    ; to fight https://bugs.chromium.org/p/chromium/issues/detail?id=355075
    (<! (messages/post-message! #js {:type "marion-close-all-tabs"}))
    (<! (messages/post-extension-command! {:command :tear-down}))))

(defn show-task-runner! []
  (messages/switch-to-runner-tab!)
  (messages/focus-runner-window!))

; this signals to the task runner that he can reconnect chrome driver and check the results
(defn send-finished-task-signal! [success?]
  (let [client-config {:name    "Signaller"
                       :on-open (fn [client]
                                  (go
                                    (<! (timeout (get-signal-client-task-result-delay)))
                                    (ws-client/send! client {:op      :task-result
                                                             :success success?})
                                    (<! (timeout (get-signal-client-close-delay)))
                                    (ws-client/close! client)))}]
    (ws-client/connect! (get-signal-server-url) client-config)))

; -- task state -------------------------------------------------------------------------------------------------------------

(defn success? []
  (= @exit-code ::success))

(defn failed? []
  @failed-state?)

(defn running? []
  (nil? @exit-code))

(defn pause-if-not-under-test-runner! []
  (if-not (helpers/automated-testing?)
    (runner/pause)))

(defn set-exit-code! [code]
  (log "set-exit-code!" code)
  (vreset! exit-code code))

(defn normalized? []
  (if-not (helpers/automated-testing?)
    (runner/normalized?)
    true))

(defn get-transcript-streamer-server-url-if-needed []
  (if (helpers/automated-testing?)
    (get-transcript-streamer-server-url)))

(defn make-failure-matcher []
  (fn [[label _message]]
    (if-not (failed?)
      (when (or (= label "fail")
                (= label "error"))
        (log "FAILURE detected")
        (vreset! failed-state? true)
        (pause-if-not-under-test-runner!)))
    false))

; -- task life-cycle --------------------------------------------------------------------------------------------------------

(defn task-setup! [& [config]]
  (when-not @setup-done                                                                                                       ; this is here to support figwheel's hot-reloading
    (vreset! setup-done true)
    (messages/init! "task-runner")
    ; transcript is a fancy name for "log of interesting events"
    (register-global-exception-handler!)
    (transcript-host/init-transcript! "transcript-box" (normalized?) (get-transcript-streamer-server-url-if-needed))
    ; when we are not running under test-runner, we want skip all future actions after a failure
    ; this helps inspection of the problems in an interactive way
    (transcript-host/register-observer! (make-failure-matcher))
    (status-host/init-status! "status-box")
    (init-cljs-printing!)
    (launcher/init!)
    ; feedback subsystem is responsible for intercepting messages to be presented in transcript
    (feedback/init!)
    (messages/reposition-runner-window!)
    ; if test runner is present, we will wait for test runner to launch the test
    ; it needs to disconnect the driver first
    (if-not (helpers/automated-testing?)
      (launcher/launch-task!))))

(defn task-teardown! []
  (assert (not (running?)))
  (let [runner-present? (helpers/automated-testing?)
        successful? (success?)]
    (go
      ; this prevents any new transcript being spit out during our teardown process, except for forced appends
      (transcript-host/disable-transcript!)
      ; under manual test development we don't want to reset-browser-state!
      ; - closing existing tabs would interfere with our ability to inspect broken test results
      ; also we don't want to signal "task finished" because  there is no test runner listening
      (if-not runner-present?
        (show-task-runner!)                                                                                                   ; this is for convenience
        (if successful?
          ; we want to close all tabs/windows opened (owned) by our extension
          ; chrome driver does not have access to those windows and fails to switch back to its own tab
          ; https://bugs.chromium.org/p/chromium/issues/detail?id=355075
          (<! (reset-browser-state!))))
      (<! (messages/wait-for-all-pending-replies-or-timeout! (get-pending-replies-wait-timeout)))
      (feedback/done!)
      (if runner-present?
        (send-finished-task-signal! successful?))                                                                             ; note: if task runner wasn't successful we leave browser in failed state for possible inspection
      successful?)))

(defn task-timeout! [data]
  (when (running?)
    (set-exit-code! ::timeout)
    (transcript-host/forced-append-to-transcript! "timeout" (:transcript data))
    (status-host/set-status! (or (:status data) "task timeouted!"))
    (status-host/set-style! (or (:style data) "timeout"))
    (transcript-host/set-style! (or (:style data) "timeout"))
    (task-teardown!)))

(defn task-exception! [message e]
  (when (running?)
    (set-exit-code! ::exception)
    (status-host/set-status! (str "task has thrown an exception: " message))
    (status-host/set-style! "exception")
    (transcript-host/forced-append-to-transcript! "exception" (utils/format-error e))
    (transcript-host/set-style! "exception")
    (task-teardown!)))

(defn task-started! []
  (go
    (status-host/set-status! "task running...")
    (status-host/set-style! "running")
    (transcript-host/set-style! "running")
    ; this will ensure we get stable devtools/scenario ids with each new run => stable transcript output
    ; this will also reset extension options to defaults!
    (<! (messages/reset-state!))
    ; when launched from test runner, chrome driver is in charge of selecting debugging port, we have to propagate this
    ; information to our dirac extension settings
    (<! (setup-debugging-port!))
    ; open-as window is handy for debugging, becasue we can open internal devtools to inspect dirac frontend in case of errors
    (<! (messages/set-options! {:open-as "window"}))))

(defn task-finished! []
  (when (running?)
    (if (failed?)
      (do
        (set-exit-code! ::failed)
        (status-host/set-status! "task failed (all actions after the failure were ignored)")
        (status-host/set-style! "failed")
        (transcript-host/set-style! "failed"))
      (do
        (set-exit-code! ::success)
        (status-host/set-status! "task finished")
        (status-host/set-style! "finished")
        (transcript-host/set-style! "finished")))
    (task-teardown!)))

(defn task-kill! []
  (when (running?)
    (set-exit-code! ::killed)
    (transcript-host/forced-append-to-transcript! "killed" "the task was killed externally")
    (status-host/set-status! "task killed!")
    (status-host/set-style! "killed")
    (transcript-host/set-style! "killed")
    (task-teardown!)))

; -- handling exceptions ----------------------------------------------------------------------------------------------------

(defn task-exception-handler! [message _source _lineno _colno e]
  (case (ex-message e)
    :task-timeout (task-timeout! (ex-data e))
    :serialized-error (task-exception! (second (ex-data e)) (nth (ex-data e) 2 "<missing stack trace>"))
    (task-exception! message e))
  false)

(defn register-global-exception-handler! []
  (gset! "onerror" task-exception-handler!))
