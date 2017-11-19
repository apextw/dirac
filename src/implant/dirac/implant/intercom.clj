(ns dirac.implant.intercom)

(defmacro error-response [id & args]
  `(cljs.core.async/go
     (~'error ~@args)
     {:op  :error
      :id  ~id
      :err (apply str ~@(interpose " " args))}))
