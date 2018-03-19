(ns dirac.automation.scenario
  (:require [oops.core :refer [oget oset! ocall oapply gget gset!]]
            [dirac.automation.logging :refer [log info warn error]]
            [cljs.pprint :refer [pprint]]
            [dirac.shared.utils]
            [dirac.automation.messages :as messages]
            [dirac.automation.notifications :as notifications]
            [dirac.shared.utils :as utils]))

(defonce triggers (atom {}))                                                                                                  ; trigger-name -> callback
(defonce original-console-api (atom nil))
(defonce feedback-transformers (atom []))                                                                                     ; a list of fns string -> string

; -- console output transformers --------------------------------------------------------------------------------------------

(defn register-feedback-transformer! [transformer]
  (swap! feedback-transformers conj transformer))

(defn unregister-feedback-transformer! [transformer]
  (swap! feedback-transformers #(remove (fn [item] (= item transformer)) %)))

(defn transform-feedback [input]
  (let [xform (fn [acc val] (val acc))]
    (reduce xform input @feedback-transformers)))

(defn feedback! [transcript & [label]]
  (messages/post-scenario-feedback! (transform-feedback transcript) label))

; -- triggers ---------------------------------------------------------------------------------------------------------------

(defn register-trigger! [name callback]
  (swap! triggers assoc (keyword name) callback))

(defn unregister-trigger! [name]
  (swap! triggers dissoc (keyword name)))

(defn call-trigger! [name args]
  (if-let [trigger-fn (get @triggers (keyword name))]
    (apply trigger-fn args)
    (warn "unrecognized trigger " name " when processing " args)))

; -- handling exceptions ----------------------------------------------------------------------------------------------------

(defn scenario-exception-handler! [_message _source _lineno _colno e]
  (feedback! (str "uncaught exception: " (utils/extract-first-line (utils/format-error e))))
  false)

(defn register-global-exception-handler! []
  (gset! "onerror" scenario-exception-handler!))

; -- notification handler ---------------------------------------------------------------------------------------------------

(defn notification-handler! [notification]
  (let [trigger-name (:trigger notification)
        args (:args notification)]
    (assert trigger-name)
    (call-trigger! trigger-name args)))

; -- facades ----------------------------------------------------------------------------------------------------------------

(defn ready! []
  (messages/init! "scenario")
  (notifications/init!)
  (register-global-exception-handler!)
  (notifications/subscribe-notifications! notification-handler!)
  (messages/send-scenario-ready!))

; -- capturing console output -----------------------------------------------------------------------------------------------

(defn console-handler [orig kind & args]
  (let [transcript (str kind args)]
    (feedback! transcript (str "scenario out"))
    (.apply orig js/console (to-array args))))

(defn store-console-api []
  {"log"   (gget "console.log")
   "warn"  (gget "console.warn")
   "info"  (gget "console.info")
   "error" (gget "console.error")})

(defn captured-console-api [original-api]
  {"log"   (partial console-handler (get original-api "log") "LOG: ")
   "warn"  (partial console-handler (get original-api "warn") "WARN: ")
   "info"  (partial console-handler (get original-api "info") "INFO: ")
   "error" (partial console-handler (get original-api "error") "ERROR: ")})

(defn set-console-api! [api]
  (gset! "console.log" (get api "log"))
  (gset! "console.warn" (get api "warn"))
  (gset! "console.info" (get api "info"))
  (gset! "console.error" (get api "error")))

(defn capture-console-as-feedback! []
  {:pre [(nil? @original-console-api)]}
  (reset! original-console-api (store-console-api))
  (set-console-api! (captured-console-api @original-console-api)))

(defn uncapture-console-as-feedback! []
  {:pre [(some? @original-console-api)]}
  (set-console-api! @original-console-api)
  (reset! original-console-api nil))
