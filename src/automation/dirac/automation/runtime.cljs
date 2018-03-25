(ns dirac.automation.runtime
  (:require [dirac.automation.logging :refer [log warn error info]]
            [oops.core :refer [oset! oget ocall oapply gset! gcall]]
            [dirac.runtime :as runtime]
            [dirac.runtime.prefs :as runtime-prefs]
            [dirac.automation.helpers :as helpers]
            [dirac.automation.devtools :refer [init-devtools!]]
            [goog.async.nextTick :as next-tick]))

(defn configure-runtime-from-url-params! [url]
  (let [params (helpers/get-matching-query-params url #"^set-")
        prefs (into {} (for [[param value] params]
                         (let [key (keyword (second (re-find #"^set-(.*)" param)))]
                           [key value])))]
    (when-not (empty? prefs)
      (warn "setting dirac runtime prefs via url params" (pr-str prefs))                                                      ; use pr-str because cljs-devtools is not yet installed
      (runtime-prefs/merge-prefs! prefs))))

(defn monkey-patch-runtime-get-version! [mock-version]
  (gset! "dirac.runtime.get_version" (constantly mock-version)))

(defn monkey-patch-runtime-repl-get-api-version! [mock-version]
  (gset! "dirac.runtime.repl.get_api_version" (constantly mock-version)))

(defn promise-based-set-immediate [callback]
  (-> (gcall "Promise.resolve")
      (ocall "then" callback))
  nil)

(defn install-async-set-immediate! []
  (set! next-tick/setImmediate_ promise-based-set-immediate))

(defn init-runtime! [& [config]]
  (init-devtools! config)
  (when (:override-goog-async-next-tick config)
    (warn ":override-goog-async-next-tick applied")
    (install-async-set-immediate!))
  (configure-runtime-from-url-params! (helpers/get-document-url))
  (when-let [runtime-prefs (:runtime-prefs config)]                                                                           ; override runtime prefs
    (warn "dirac runtime prefs override:" (pr-str runtime-prefs))                                                             ; use pr-str because cljs-devtools is not yet installed
    (runtime-prefs/merge-prefs! runtime-prefs))
  (if-not (:do-not-install-runtime config)                                                                                    ; override devtools features/installation
    (let [features-to-enable (cond-> []
                               (not (:do-not-enable-repl config)) (conj :repl))]
      (runtime/install! features-to-enable))
    (warn "dirac runtime override: do not install"))
  (if (:old-runtime config)
    (monkey-patch-runtime-get-version! "0.0.1"))
  (if (:future-runtime config)
    (monkey-patch-runtime-get-version! "1000.0.1"))
  (if (:old-repl-api config)
    (monkey-patch-runtime-repl-get-api-version! 0))
  (if (:future-repl-api config)
    (monkey-patch-runtime-repl-get-api-version! 1000)))
