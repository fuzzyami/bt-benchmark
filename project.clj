(defproject benchmark-bt "0.1.0"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [com.cycognito/hamurai "0.0.18"]
                 [com.cycognito/floob-clj "0.2.5-read-from-bt-9"]
                 [com.stuartsierra/component "1.0.0"]
                 [ring "1.8.2"]
                 [clj-http "3.12.0"]
                 [environ "1.2.0"]
                 [com.taoensso/tufte "2.2.0"]
                 [com.taoensso/encore "3.10.1"]]
  ;:main ^:skip-aot benchmark-bt.core
  :main benchmark-bt.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :plugins [[lein-parent "0.3.8"]]
  :repositories [["snapshots" {:url      "https://maven.cyco.fun/snapshots"
                             :username :env/cyco_maven_username
                             :password :env/cyco_maven_password}]
               ["releases" {:url      "https://maven.cyco.fun/releases"
                            :username :env/cyco_maven_username
                            :password :env/cyco_maven_password}]]

  :parent-project {:coords [com.cycognito/cyco-parent-clj "0.0.154"]
                   :inherit [:dependencies
                             :managed-dependencies
                             :plugins
                             :bom]}
  :ring {:handler benchmark-bt.core/handler})