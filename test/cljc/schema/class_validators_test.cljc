(ns schema.class-validators-test
  "Tests for schema.

   Uses helpers defined in schema.test-macros (for cljs sake):
    - (valid! s x) asserts that (s/check s x) returns nil
    - (invalid! s x) asserts that (s/check s x) returns a validation failure
      - The optional last argument also checks the printed Clojure representation of the error.
    - (invalid-call! s x) asserts that calling the function throws an error."
  (:refer-clojure :exclude [parse-long])
  #?(:clj (:use clojure.test [schema.test-macros :only [valid! invalid! invalid-call! is-assert!]]))
  #?(:cljs (:use-macros
             [cljs.test :only [is deftest testing are]]
             [schema.test-macros :only [valid! invalid! invalid-call! is-assert!]]))
  #?(:cljs (:require-macros [clojure.template :refer [do-template]]
                            [schema.macros :as macros]))
  (:require
   [clojure.string :as str]
   [#?(:clj clojure.pprint
       :cljs cljs.pprint) :as pprint]
   #?(:clj [clojure.template :refer [do-template]])
   clojure.data
   [schema.utils :as utils]
   [schema.core :as s]
   [schema.other-namespace :as other-namespace]
   [schema.spec.core :as spec]
   [schema.spec.collection :as collection]
   #?(:clj [schema.macros :as macros])
   #?(:cljs cljs.test)))

;;TODO more efficient schemas for type hints. can compile directly to most efficient thing in this case.
;; e.g., (s/fn [^foo.C c] ...) => (let [v (validator (variant/variant-spec #(instance? foo.C %) [{:schema 'foo.C]))] (fn [c] (v c) ...)
;; e.g., perhaps similar for (s/fn [c :- foo.C] ...) and (s/fn [c :- #'C-schema] ...)
;;TODO change cross-platform Class schemas to variant-like schemas that also know how to type-hint themselves.
#_
(macros/defrecord-schema Predicate [p? pred-name type-hint]
  TypeHintableSchema
  (type-hint [_] type-hint))
#_
(extend-protocol TypeHintableSchema
  Var
  (type-hint [v] @v)
  Class
  (type-hint [c] c)
  Object
  (type-hint [_]))
;; e.g., (def Str (variant-spec))
#_
(deftest )
