(ns marion.figwheel
  (:require [figwheel.client :as figwheel]
            [dirac.shared.utils :refer [runonce]]))

; -------------------------------------------------------------------------------------------------------------------
; has to be included before boot

(runonce
  (figwheel/start
    {:websocket-url "ws://localhost:7200/figwheel-ws"}))
