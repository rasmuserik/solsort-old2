(ns solsort.router
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require
    [solsort.registry :as registry :refer [local-mbox? call-local local-mboxes]]
    [solsort.util :refer [chan?]]
    [solsort.html :refer [render-jsonhtml]]
    [solsort.system :as system :refer [log is-browser]]
    ))

(enable-console-print!)


(def arg
  (or
    (and (exists? js/global) js/global.process (get js/global.process.argv 2))
    (and (exists? js/window) js/window js/window.location (.slice js/window.location.hash 1))))


(system/set-immediate 
  (fn []
    (go
      (log 'routes 'starting arg)
      (if (local-mbox? arg)
        (let [result (call-local arg)
              result (if (chan? result) (<! result) result)]
          (if (and is-browser (= "json-html" (aget result "type")))
            (render-jsonhtml result))
          result)
        (log 'routes 'no-such-route arg (local-mboxes))))))

