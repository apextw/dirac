(ns dirac.automation.helpers
  (:require [cljs.core.async :refer [put! <! chan timeout alts! close! go go-loop]]
            [oops.core :refer [oget oset! ocall oapply gcall! gget]]
            [cuerdas.core :as cuerdas])
  (:import goog.Uri))

(defn get-body-el []
  (-> js/document (.getElementsByTagName "body") (.item 0)))

(defn get-el-by-id [id]
  (-> js/document (.getElementById id)))

(defn make-uri-object [url]
  (Uri. url))

(defn get-query-param [url param]
  (let [uri (make-uri-object url)]
    (.getParameterValue uri param)))

(defn get-matching-query-params [url re]
  (let [uri (make-uri-object url)
        query (.getQueryData uri)
        matching-params (filter #(re-find re %) (.getKeys query))]
    (into {} (map (fn [key] [key (.get query key)]) matching-params))))

(defn get-encoded-query [url]
  (let [uri (make-uri-object url)
        encoded-query (.getEncodedQuery uri)]
    encoded-query))

(defn get-document-url []
  (str (.-location js/document)))

(defn get-document-url-param [param]
  (let [url (get-document-url)]
    (get-query-param url param)))

(defn automated-testing? []
  (let [url (get-document-url)]
    (boolean (get-query-param url "test_runner"))))

(defn prefix-text-block [prefix text]
  (->> text
       (cuerdas/lines)
       (map-indexed (fn [i line] (if-not (zero? i) (str prefix line) line)))                                                  ; prepend prefix to all lines except the first
       (cuerdas/unlines)))

(defn get-base-url []
  (str (gget "location.protocol") "//" (gget "location.host")))

(defn get-scenario-url [name & [additional-params]]
  (let [base-params (get-encoded-query (get-document-url))
        all-params (if additional-params (str base-params "&" additional-params) base-params)]
    (str (get-base-url) "/scenarios/" name ".html?" all-params)))                                                             ; we pass all query parameters to scenario page

(defn scroll-page-to-bottom! []
  (gcall! "scrollTo" 0 (gget "document.body.scrollHeight")))
