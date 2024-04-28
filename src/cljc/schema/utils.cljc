(ns schema.utils
  "Private utilities used in schema implementation."
  (:refer-clojure :exclude [record?])
  #?(:clj (:require [clojure.string :as string])
     :cljs (:require
             goog.string.format
             [goog.object :as gobject]
             [goog.string :as gstring]
             [clojure.string :as string]))
  #?(:cljs (:require-macros [schema.utils :refer [char-map]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Miscellaneous helpers

(defn assoc-when
  "Like assoc but only assocs when value is truthy.  Copied from plumbing.core so that
   schema need not depend on plumbing."
  [m & kvs]
  (assert (even? (count kvs)))
  (into (or m {})
        (for [[k v] (partition 2 kvs)
              :when v]
          [k v])))

(defn type-of [x]
  #?(:clj (class x)
     :cljs (js* "typeof ~{}" x)))

(defn fn-schema-bearer
  "What class can we associate the fn schema with? In Clojure use the class of the fn; in
   cljs just use the fn itself."
  [f]
  #?(:bb f
     :clj (class f)
     :cljs f))

(defn format* [fmt & args]
  (apply #?(:clj format :cljs gstring/format) fmt args))

(def max-value-length (atom 19))

(defn value-name
  "Provide a descriptive short name for a value."
  [value]
  (let [t (type-of value)]
    (if (<= (count (str value)) @max-value-length)
      value
      (symbol (str "a-" #?(:clj (.getName ^Class t) :cljs t))))))

#?(:clj
(defmacro char-map []
  clojure.lang.Compiler/CHAR_MAP))

#?(:clj
(defn unmunge
  "TODO: eventually use built in demunge in latest cljs."
  [s]
  (->> (char-map)
       (sort-by #(- (count (second %))))
       (reduce (fn [^String s [to from]] (string/replace s from (str to))) s))))

(defn fn-name
  "A meaningful name for a function that looks like its symbol, if applicable."
  [f]
  #?(:cljs
     (let [[_ s] (re-matches #"#object\[(.*)\]" (pr-str f))]
       (if (= "Function" s)
         "function"
         (->> s demunge (re-find #"[^/]+(?:$|(?=/+$))"))))
     :clj (let [s (.getName (class f))
                slash (.lastIndexOf s "$")
                raw (unmunge
                      (if (>= slash 0)
                        (str (subs s 0 slash) "/" (subs s (inc slash)))
                        s))]
            (string/replace raw #"^clojure.core/" ""))))

(defn record? [x]
  #?(:clj (instance? clojure.lang.IRecord x)
     :cljs (satisfies? IRecord x)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Error descriptions

;; A leaf schema validation error, describing the schema and value and why it failed to
;; match the schema.  In Clojure, prints like a form describing the failure that would
;; return true.

(declare validation-error-explain)

(deftype ValidationError [schema value expectation-delay fail-explanation]
  #?(:cljs IPrintWithWriter)
  #?(:cljs (-pr-writer [this writer opts]
             (-pr-writer (validation-error-explain this) writer opts))))

(defn validation-error-explain [^ValidationError err]
  (list (or (.-fail-explanation err) 'not) @(.-expectation-delay err)))

#?(:clj ;; Validation errors print like forms that would return false
(defmethod print-method ValidationError [err writer]
  (print-method (validation-error-explain err) writer)))

(defn make-ValidationError
  "for cljs sake (easier than normalizing imports in macros.clj)"
  [schema value expectation-delay fail-explanation]
  (ValidationError. schema value expectation-delay fail-explanation))


;; Attach a name to an error from a named schema.
(declare named-error-explain)

(deftype NamedError [name error]
  #?(:cljs IPrintWithWriter)
  #?(:cljs (-pr-writer [this writer opts]
             (-pr-writer (named-error-explain this) writer opts))))

(defn named-error-explain [^NamedError err]
  (list 'named (.-error err) (.-name err)))

#?(:clj ;; Validation errors print like forms that would return false
(defmethod print-method NamedError [err writer]
  (print-method (named-error-explain err) writer)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Monoidish error containers, which wrap errors (to distinguish from success values).

(defrecord ErrorContainer [error])

(defn error
  "Distinguish a value (must be non-nil) as an error."
  [x] (assert x) (->ErrorContainer x))

(defn error? [x]
  (instance? ErrorContainer x))

(defn error-val [x]
  (when (error? x)
    (.-error ^ErrorContainer x)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Registry for attaching schemas to classes, used for defn and defrecord

#?(:clj
(let [^java.util.Map +class-schemata+ (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))]
  (defn declare-class-schema!
    "Globally set the schema for a class (above and beyond a simple instance? check).
   Use with care, i.e., only on classes that you control.  Also note that this
   schema only applies to instances of the concrete type passed, i.e.,
   (= (class x) klass), not (instance? klass x)."
    [klass schema]
    #?(:bb nil ;; fn identity is used as klass in bb
       :default (assert (class? klass)
                        (format* "Cannot declare class schema for non-class %s" (pr-str (class klass)))))
    (.put +class-schemata+ klass schema))

  (defn class-schema
    "The last schema for a class set by declare-class-schema!, or nil."
    [klass]
    (.get +class-schemata+ klass))))

#?(:cljs
(do
  (defn declare-class-schema! [klass schema]
    (gobject/set klass "schema$utils$schema" schema))

  (defn class-schema [klass]
    (gobject/get klass "schema$utils$schema"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Registry for caching Schema's of classes and other non-IMeta's used for Schema syntax

#?(:clj
(let [^java.util.Map +id->syntax-schema+ (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))]
  (defn declare-syntax-schema!
    "Cache the schema for primitive (non-IMeta) Schema syntax."
    [id syntax-schema]
    (when-not (class-schema syntax-schema)
      (.put +id->syntax-schema+ id syntax-schema))
    syntax-schema)

  (defn get-syntax-schema
    "The a syntax-schema for schema syntax id or nil."
    [id]
    (.get +id->syntax-schema+ id))))

#?(:cljs
(do
  (defn declare-syntax-schema! [id syntax-schema]
    (gobject/set id "schema$utils$syntax_schema" syntax-schema)
    syntax-schema)

  (defn get-syntax-schema [id]
    (gobject/get id "schema$utils$syntax_schema"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Registry for fast class predicates

#?(:clj
   (let [class->pred (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))]
     (defn register-class-pred! [cls pred]
       (when (class? cls)
         (.put class->pred cls pred))
       pred)
     (defn get-class-pred [cls]
       (or (.get class->pred cls)
           (register-class-pred! cls (eval `#(instance? ~cls %)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Registry for precomputed checkers

#?(:clj
   (let [schema->checker (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))]
     (defn register-schema-checker! [s checker]
       (.put schema->checker s checker)
       checker)
     (defn get-schema-checker [s]
       (.get schema->checker s)))
   :cljs 
   (do
     (defn register-schema-checker! [s checker]
       (gobject/set id "schema$core$checker" checker)
       checker)
     (defn get-schema-checker [s]
       (gobject/get s "schema$core$checker"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities for fast-as-possible reference to use to turn fn schema validation on/off

(def use-fn-validation
  "Turn on run-time function validation for functions compiled when
   s/compile-fn-validation was true -- has no effect for functions compiled
   when it is false."
  ;; specialize in Clojure for performance
  #?(:bb (atom false)
     :clj (java.util.concurrent.atomic.AtomicReference. false)
     :cljs (atom false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cached fields

(defn soft-delay* [f]
  #?(:clj (let [v (volatile! nil)]
            (reify
              clojure.lang.IDeref
              (deref [_]
                (or (when-some [^java.lang.ref.SoftReference r @v]
                      (.get r))
                    (let [res (f)]
                      (vreset! v (java.lang.ref.SoftReference. res))
                      res)))))
     :cljs (reify
             cljs.core/IDeref
             ;;TODO WeakRef? https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/WeakRef
             (-deref [_] (f)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers to create cached fields in Schema records

;; this gives us something similar to deftype's mutable fields or reify's closures,
;; without having to change our public-facing API.
;; in schema.core to keep all defn's private even though we provide a macro

(let #?(:clj [field->Map (let [+cached-record-specs+ (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))
                               +cached-record-explains+ (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))]
                           #(case %
                              :spec +cached-record-specs+
                              :explain +cached-record-explains+))]
        :cljs [->id (fn [cls-name field]
                      (str "schema$core$" cls-name "$" (name field)))])
  (defn ^:internal -set-cached-record-field-fn [this cls-name field this->v]
    (let [f (let [d (soft-delay* #(this->v this))]
              (fn [this']
                (when (identical? this this')
                  (assert d)
                  @d)))
          ;; we use mutable maps so we can always update the cache
          ;; on a cache-miss. if a library creates a spec using the position
          ;; ctor, we then don't need to care if they initialize a cache field,
          ;; which also helps ensure backwards compatibility.
          _ #?(:clj (let [^java.util.Map weak (field->Map field)]
                      (.put weak this f)
                      f)
               :cljs (gobject/set this (->id cls-name field) f))]
      f))

  (defn ^:internal -get-cached-record-field [this cls-name field this->v]
    (or (when-some [f #?(:clj (let [^java.util.Map weak (field->Map field)]
                                (.get weak this))
                         :cljs (gobject/get this (->id cls-name field)))]
          (f this))
        ((-set-cached-record-field-fn
           this cls-name field this->v)
         this))))

(defn ^:internal -construct-cached-schema-record [this cls-name this->spec this->explain]
  (assert (symbol? cls-name))
  (doto this
    (-set-cached-record-field-fn cls-name :spec this->spec)
    (-set-cached-record-field-fn cls-name :explain this->explain)))
