(ns benchmark-bt.core
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [hamurai.component :as hamurai-comp]
            [hamurai.core :as hamurai-core]
            [floob.core :as floob]
            [clj-http.client :as client]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [clj-time.core :as time :refer [days ago]]
            [taoensso.encore :as enc :refer (have have?)]
            [benchmark-bt.assets-list :refer [get-random-asset assets-list]]
            [taoensso.tufte :as tufte :refer (defnp p profiled profile add-accumulating-handler! format-grouped-pstats)]
            [taoensso.encore :as enc]))

(def version-string "hamurai-18")

(def config {:project  "cyco-main"
             :instance "qa-instance"})

(def hamurai-atom (atom {:instance nil}))

(defn init-assets [filename]
  (swap! assets-list merge  (-> (slurp filename)
                                (clojure.string/split-lines))))

(defn init-hamurai [config]
  (swap! hamurai-atom merge {:instance (component/start (hamurai-comp/map->Hamurai config))}))


(def schema-by-tablename
  {"krazy-edges-src" {:families {"data" {:default {:value-type :string}
                                         :columns {"src"        {:value-type :string}
                                                   "dst"        {:value-type :string}
                                                   "type"       {:value-type :string}
                                                   "samples"    {:s11n :frdy}
                                                   ;"samples" {:value-type :string}
                                                   "from-time"  {:value-type :time}
                                                   "to-time"    {:value-type :time}
                                                   "updated-at" {:value-type :time}
                                                   "created-at" {:value-type :time}
                                                   "status"     {:s11n :frdy}}}}
                      ;"status" {:value-type :string}}}}
                      :row-key  {:key-type  :compound
                                 :delimiter "@"
                                 :parts     [{:key-type :string
                                              :length   :variable}
                                             {:key-type :string
                                              :length   :variable}
                                             {:key-type :string
                                              :length   :variable}]}}
   })

(defonce my-sacc (add-accumulating-handler! "*"))

(def max-rows-to-fetch 100000)

(defn query-mongo [src record-type]
  (profile {:when true}
           (p ::query-rows
              (let [config {:tinkles {:key "key" :url "http://engine-api.cycognito.com/tinkles/"}}
                    query [["src" "==" src] ["updated_at" ">" {"$time" (str (-> 60 days ago))}] ["type" "==" record-type]]
                    results (floob/query-records config :cyco.edge-state query nil nil nil)]
                results))))

(defn scan-rows [hamurai-instance tablename row-key-prefix]
  (profile {:when true}
           (try
             (p ::scan-rows
                (let [rows (doall (hamurai-core/scan-rows hamurai-instance tablename
                                                          {:schema      {tablename (get schema-by-tablename tablename)}
                                                           :limit       max-rows-to-fetch
                                                           :starts-with row-key-prefix}))
                      text (clojure.string/join "\n" rows)]
                  (do
                    (timbre/infof "scanned with prefix: %s got %d rows" row-key-prefix (count rows))
                    rows)))
             (catch Exception e (timbre/error "failed scanning rows from bt:" e)))))

(defn send-request [asset db]
  (profile {:when true}
           (let [api-key (env :api-key)
                 resonse
              (if (= db "bt")
                (p ::krazy-bt (client/get (str "http://engine-api.cycognito.com/krazy8/" asset "?key=" api-key "&read-from-bt=true") {:accept :json}))
                (p ::krazy-mongo (client/get (str "http://engine-api.cycognito.com/krazy8/" asset "?key=" api-key "&read-from-bt=false") {:accept :json})))]
             resonse)))


      (defn handler [request]
  (let [url-params (:query-params request)
        rowkey (get url-params "rowkey")
        action (get url-params "action")
        src (get url-params "src")
        record-type (get url-params "type")]
    (cond
      (= "time" action)
      (let [results (scan-rows (:instance @hamurai-atom) "krazy-edges-src" [rowkey])]
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (str "fetched " (count results) " rows")})

      (= "raw" action)
      (let [results (scan-rows (:instance @hamurai-atom) "krazy-edges-src" [rowkey])]
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (str (first results))})

      (= "report" action)
      (let [stats @my-sacc]
        (if (not-empty stats)
          (let [output (with-out-str (clojure.pprint/pprint (tufte/format-grouped-pstats stats)))]
            {:status  200
             :headers {"Content-Type" "text/h tml"}
             :body    (str version-string " stats: \n" output)})

          {:status  200
           :headers {"Content-Type" "text/html"}
           :body    "nothing to report"}))

      (= "mongo-raw" action)
      (let [results (query-mongo src record-type)]
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (str "results-from-mongo:" (str/join results))})

      (= "mongo-time" action)
      (let [results (query-mongo src record-type)]
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (str "results-from-mongo: " (count results) " records")})

      (= "krazy" action)
      (let [asset (get-random-asset)
            bt-response (send-request asset "bt")
            mongo-response (send-request asset "mongo")]
        (do
          (timbre/infof "the asset %s" asset)
            {:status  200
             :headers {"Content-Type" "text/html"}
             :body    (str "asset:" asset " size-bt: " (:length bt-response) " size-mongo: " (:length mongo-response))}))
      )))

(def app-handler
  (-> handler
      (params/wrap-params {:encoding "UTF-8"})))

(defn -main
  [& args]
  (let [api-key (env :api-key)] ; edit the .lein-env file
    (if-not api-key
      (throw (Exception. "API_KEY must be defined in env"))))
  (init-hamurai config)
  (init-assets "assets.txt")
  (let [config {:tinkles {:key "key" :url "http://engine-api.cycognito.com/tinkles/"}}]
    (floob.core/init-schemas config))
  (jetty/run-jetty app-handler {:port 3000 :join? true}))
