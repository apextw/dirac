(ns dirac.automation.triggers
  (:require [dirac.shared.async :refer [go-wait go <!]]
            [oops.core :refer [oget oset! ocall oapply gcall! ocall! gcall gset!]]
            [dirac.automation.scenario :as scenario]))

; -- helpers ----------------------------------------------------------------------------------------------------------------

(defn go-reload-safely! []
  (go
    ; this delay is here to prevent "Cannot find context with specified id" V8 errors ?
    (<! (go-wait 1000))                                                                                                       ; TODO: should not be hard-coded FLAKY!
    (gcall! "location.reload")))

; -- triggers installers ----------------------------------------------------------------------------------------------------

(defn install-reload! []
  (scenario/register-trigger! :reload go-reload-safely!))

(defn install-navigate! []
  (scenario/register-trigger! :navigate #(gset! "location.pathname" %)))

(defn install-eval! []
  (scenario/register-trigger! :eval-cljs #(gcall! "dirac.runtime.repl.request_eval_cljs" %))
  (scenario/register-trigger! :eval-js #(gcall! "dirac.runtime.repl.request_eval_js" %)))

; -- common trigger groups --------------------------------------------------------------------------------------------------

(defn install-common-triggers! []
  (install-reload!)
  (install-navigate!)
  (install-eval!))
