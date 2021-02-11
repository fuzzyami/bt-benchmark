(ns benchmark-bt.assets-list)

(def assets-list (atom []))

(defn get-random-asset []
  (rand-nth (rand-nth @assets-list)))