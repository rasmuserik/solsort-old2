(ns solsort.lib.kvdb
  (:require-macros [cljs.core.async.macros :refer [go alt! go-loop]])
  (:require
    [solsort.lib.old-kvdb :as old-kvdb]
    [solsort.sys.platform :refer [is-nodejs is-browser ensure-dir]]
    [solsort.sys.mbox :refer [route log]]
    [cljs.reader :refer [read-string]]
    [cljs.core.async :refer [>! <! chan put! take! timeout close!]]))



(def dbs (atom {}))


(when is-nodejs
  (defn open-db [db]
    (log 'kvdb 'open-db db)
    (go))
  (defn execute-transaction [queries stores]
    (go))
  )


(when is-browser
  (def indexed-db (atom nil))
  (defn open-db [db]
    (if @indexed-db (.close @indexed-db))
    (let [c (chan)
          store-list (conj (set (read-string (or (.getItem js/localStorage "kvdbs") ""))) db)
          req (.open js/indexedDB "kvdb" (inc (count store-list)))]
      (reset! dbs store-list)
      (.setItem js/localStorage "kvdbs" (str store-list))
      (log 'kvdb 'open-db db store-list)
      (set! (.-onupgradeneeded req)
            (fn [req]          
              (let [db (.-result (.-target req))]
                (doall (for [store store-list]
                         (if (not (.contains (.-objectStoreNames db) store))
                           (.createObjectStore db store)))))))
      (set! (.-onerror req) 
            (fn [err]
              (log 'kvdb 'upgrade-error)
              (js/console.log 'error err)))
      (set! (.-onsuccess req) 
            (fn [req]
              (reset! indexed-db (.-result (.-target req)))
              (close! c)))
      c))
  (defn execute-transaction [queries stores]
    (let [c (chan)
          read-only (= 0 (count stores))
          dbs (into (into #{} (keys queries)) (keys stores))
          _ (log 'dbs dbs)
          transaction (.transaction @indexed-db 
                                    (clj->js (seq dbs))
                                    (if read-only "readonly" "readwrite"))
          ]
      (log 'transaction queries stores dbs read-only)
      ;TODO
      (close! c)
      c)))


(declare commit)
(def cache (atom {})) ; stores enqueuede
(def store-count (atom 0)) ; number of unexecuted stores
(def queries (atom {})) 
(def transaction-listeners (atom []))

(def prev-cache (atom {})) ; stores currently being executed
(def transaction-request (chan 1))

(defn run-transaction [queries stores]
  (go
    (log 'kvdb 'run-transaction queries stores)
    (loop [db-list (seq (into (into #{} (keys queries)) (keys stores)))]
      (when (first db-list)
        (when-not (contains? dbs (first db-list))
          (<! (open-db (first db-list))))
        (recur (rest db-list))))
    (when (< 0 (+ (count queries) (count stores)))
      (<! (execute-transaction queries stores)))))

(defn transaction-loop []
  (go
    (loop []
      (<! transaction-request)
      ; NB: not thread safe
      (let [listeners @transaction-listeners
            qs @queries
            stores @cache]
        (reset! prev-cache @cache)
        (reset! cache {})
        (reset! store-count 0)
        (reset! queries {})
        (reset! transaction-listeners [])
        (<! (run-transaction qs stores))
        (loop [listeners listeners]
          (when (first listeners)
            (put! (first listeners) true)
            (recur (rest listeners))))
        (recur)))))
(transaction-loop)

(defn transact [] (put! transaction-request true))
(defn db-fetch [db k]
  (let [c (chan 1)]
    (swap! queries
           assoc-in [db k]
           (conj (get-in @queries [db k] '()) c))
    (transact)
    c))


(defn store [db k v]
  (swap! cache assoc-in [db k] v)
  (when (= @store-count 0) (transact))
  (swap! store-count inc)
  (if (< @store-count 1000) (go) (commit)))

(defn fetch [db k]
  (go (or (get-in @cache [db k])
          (get-in @prev-cache [db k])
          (<! (db-fetch db k)))))

(when is-browser
  (fetch "a" 'b)
  (fetch "a" 'b)
  (store "foo" :bar :baz)
  (go
    (timeout 100)
    (log 'kvdb-queries queries)
    (log 'kvdb-cache cache)
    ))
(defn commit []
  (let [c (chan 1)]
    (swap! conj transaction-listeners c)
    (transact)
    c))


;; Generic functions
(defn store-channel [db c]
  (go-loop
    [key-val (<! c)]
    (if key-val
      (let [[k v] key-val]
        (<! (store db k (clj->js v)))
        (recur (<! c)))
      (<! (commit)))))

(defn multifetch [storage ids]  
  (let [c (chan 1)      
        result #js{}      
        cnt (atom (count ids))]
    (if (= 0 @cnt)
      (close! c)
      (doall (for [id ids]
               (take! (fetch storage id)
                      (fn [value]
                        (aset result id value)
                        (if (<= (swap! cnt dec) 0)
                          (put! c (js->clj result))))))))
    c))


