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
  (pred [{:keys [pred]} params]
    (assert pred)
    pred)
  spec/PrePredSpec
  (pre-pred [{:keys [pred]} params]
    (assert pred)
    pred))

(defn leaf-spec
  "A leaf spec represents an atomic datum that is checked completely
   with a single precondition, and is otherwise a black box to Schema."
  ([pre ;- spec/Precondition
    ]
   (->LeafSpec pre))
  ([pre ;- spec/Precondition
    pred]
   (assoc (->LeafSpec pre) :pred pred)))
