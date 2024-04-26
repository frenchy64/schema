(ns schema.spec.leaf
  (:require
   [schema.spec.core :as spec]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Leaf Specs

(defrecord LeafSpec [pre]
  spec/CoreSpec
  (subschemas [this] nil)
  (checker [this params]
    (fn [x] (or (pre x) x)))
  spec/PredSpec
  (pred [this params] (or (:pred this)
                          (println "WARNING: no pred")
                          #_(comp nil? pre))))

(defn leaf-spec
  "A leaf spec represents an atomic datum that is checked completely
   with a single precondition, and is otherwise a black box to Schema."
  [pre ;- spec/Precondition
   ]
  (->LeafSpec pre))
