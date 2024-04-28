(ns schema.core
  "A library for data shape definition and validation. A Schema is just Clojure data,
   which can be used to document and validate Clojure functions and data.

   For example,

   (def FooBar {:foo Keyword :bar [Number]}) ;; a schema

   (check FooBar {:foo :k :bar [1.0 2.0 3.0]})
   ==> nil

   representing successful validation, but the following all return helpful errors
   describing how the provided data fails to measure up to schema FooBar's standards.

   (check FooBar {:bar [1.0 2.0 3.0]})
   ==> {:foo missing-required-key}

   (check FooBar {:foo 1 :bar [1.0 2.0 3.0]})
   ==> {:foo (not (keyword? 1))}

   (check FooBar {:foo :k :bar [1.0 2.0 3.0] :baz 1})
   ==> {:baz disallowed-key}

   Schema lets you describe your leaf values using the Any, Keyword, Symbol, Number,
   String, and Int definitions below, or (in Clojure) you can use arbitrary Java
   classes or primitive casts to describe simple values.

   From there, you can build up schemas for complex types using Clojure syntax
   (map literals for maps, set literals for sets, vector literals for sequences,
   with details described below), plus helpers below that provide optional values,
   enumerations, arbitrary predicates, and more.

   Assuming you (:require [schema.core :as s :include-macros true]),
   Schema also provides macros for defining records with schematized elements
   (s/defrecord), and named or anonymous functions (s/fn and s/defn) with
   schematized inputs and return values.  In addition to producing better-documented
   records and functions, these macros allow you to retrieve the schema associated
   with the defined record or function.  Moreover, functions include optional
   *validation*, which will throw an error if the inputs or outputs do not
   match the provided schemas:

   (s/defrecord FooBar
    [foo :- Int
     bar :- String])

   (s/defn quux :- Int
    [foobar :- Foobar
     mogrifier :- Number]
    (* mogrifier (+ (:foo foobar) (Long/parseLong (:bar foobar)))))

   (quux (FooBar. 10 \"5\") 2)
   ==> 30

   (fn-schema quux)
   ==> (=> Int (record user.FooBar {:foo Int, :bar java.lang.String}) java.lang.Number)

   (s/with-fn-validation (quux (FooBar. 10.2 \"5\") 2))
   ==> Input to quux does not match schema: [(named {:foo (not (integer? 10.2))} foobar) nil]

   As you can see, the preferred syntax for providing type hints to schema's defrecord,
   fn, and defn macros is to follow each element, argument, or function name with a
   :- schema.  Symbols without schemas default to a schema of Any.  In Clojure,
   class (e.g., clojure.lang.String) and primitive schemas (long, double) are also
   propagated to tag metadata to ensure you get the type hinting and primitive
   behavior you ask for.

   If you don't like this style, standard Clojure-style typehints are also supported:

   (fn-schema (s/fn [^String x]))
   ==> (=> Any java.lang.String)

   You can directly type hint a symbol as a class, primitive, or simple
   schema.

   See the docstrings of defrecord, fn, and defn for more details about how
   to use these macros."
  ;; don't exclude def because it's not a var.
  (:refer-clojure :exclude [Keyword Symbol Inst atom defprotocol defrecord defn letfn defmethod fn MapEntry ->MapEntry any?])
  (:require
   #?(:clj [clojure.pprint :as pprint])
   [clojure.string :as str]
   #?(:clj [schema.macros :as macros])
   [schema.utils :as utils]
   [schema.spec.core :as spec :include-macros true]
   [schema.spec.leaf :as leaf]
   [schema.spec.variant :as variant]
   [schema.spec.collection :as collection]
   [clojure.core :as cc])
  #?(:cljs (:require-macros [schema.macros :as macros]
                            schema.core)))

#?(:clj (set! *warn-on-reflection* true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema protocol

(clojure.core/defprotocol Schema
  (spec [this]
    "A spec is a record of some type that expresses the structure of this schema
     in a declarative and/or imperative way.  See schema.spec.* for examples.")
  (explain [this]
    "Expand this schema to a human-readable format suitable for pprinting,
     also expanding class schematas at the leaves.  Example:

     user> (s/explain {:a s/Keyword :b [s/Int]} )
     {:a Keyword, :b [Int]}"))

(clojure.core/defprotocol SchemaSyntax
  (original-syntax [this] "Returns the original syntax for a schema. Returns this if none."))

(extend-protocol SchemaSyntax
  nil
  (original-syntax [_] nil)
  #?(:clj Object :cljs default)
  (original-syntax [this] this))


#?(:clj
(clojure.core/defn register-schema-print-as-explain [t]
  (clojure.core/defmethod print-method t [s writer]
    (print-method (explain s) writer))
  (clojure.core/defmethod pprint/simple-dispatch t [s]
    (pprint/write-out (explain s)))))

;; macros/defrecord-schema implements print methods in bb/cljs
#?(:bb nil
   :clj (do (register-schema-print-as-explain schema.core.Schema)
            (doseq [m [print-method pprint/simple-dispatch]]
              (prefer-method m schema.core.Schema clojure.lang.IRecord)
              (prefer-method m schema.core.Schema java.util.Map)
              (prefer-method m schema.core.Schema clojure.lang.IPersistentMap))))

(clojure.core/defn checker
  "Compile an efficient checker for schema, which returns nil for valid values and
   error descriptions otherwise."
  [schema]
  (comp utils/error-val
        (spec/run-checker
         (clojure.core/fn [s params] (spec/checker (spec s) params)) false schema)))

(clojure.core/defn check
  "Return nil if x matches schema; otherwise, returns a value that looks like the
   'bad' parts of x with ValidationErrors at the leaves describing the failures.

   If you will be checking many datums, it is much more efficient to create
   a 'checker' once and call it on each of them."
  [schema x]
  ((checker schema) x))

(clojure.core/defn validator
  "Compile an efficient validator for schema."
  [schema]
  (let [c (checker schema)]
    (clojure.core/fn [value]
      (when-let [error (c value)]
        (macros/error! (utils/format* "Value does not match schema: %s" (pr-str error))
                       {:schema schema :value value :error error}))
      value)))

(clojure.core/defn validate
  "Throw an exception if value does not satisfy schema; otherwise, return value.
   If you will be validating many datums, it is much more efficient to create
   a 'validator' once and call it on each of them."
  [schema value]
  ((validator schema) value))

(defn- any? [_] true)
(defn- never? [_] false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Platform-specific leaf Schemas

;; On the JVM, a Class itself is a schema. In JS, we treat functions as prototypes so any
;; function prototype checks objects for compatibility. In BB, defrecord classes can also be
;; instances of sci.lang.Type, and the interpreter extends `instance?` to support it as first arg.

(defn- instance-pred [klass]
  #?(:bb #(instance? klass %)
     :clj (or (utils/get-class-pred klass)
              (try (utils/register-class-pred! klass (eval `#(instance? ~klass %)))
                   (catch Exception _ #(instance? klass %))))
     :cljs #(and (not (nil? %))
                 (or (identical? klass (.-constructor %))
                     (js* "~{} instanceof ~{}" % klass)))))

(clojure.core/defn instance-precondition
  ([s klass] (instance-precondition s klass (instance-pred klass)))
  ([s klass pred] (spec/precondition s pred #(list 'instance? klass %))))

(defn- -class-spec [this]
  (let [pre (instance-precondition this this)]
    (if-let [class-schema (utils/class-schema this)]
      (variant/variant-spec pre [{:schema class-schema}])
      (leaf/leaf-spec pre))))

(defn- -class-explain [this]
  (if-let [more-schema (utils/class-schema this)]
    (explain more-schema)
    (condp = this
      #?@(:clj [java.lang.String 'Str])
      #?(:clj java.lang.Boolean :cljs js/Boolean) 'Bool
      #?(:clj java.lang.Number :cljs js/Number) 'Num
      #?@(:clj [java.util.regex.Pattern 'Regex])
      #?(:clj java.util.Date :cljs js/Date) 'Inst
      #?(:clj java.util.UUID :cljs cljs.core/UUID) 'Uuid
      #?(:clj (or #?(:bb (when (instance? sci.lang.Type this)
                           (symbol (str this))))
                  (symbol (.getName ^Class this)))
         :cljs this))))

(defn- -class-schema [this]
  (or (utils/get-syntax-schema this)
      (utils/declare-syntax-schema!
        this
        (let [sp (delay (-class-spec this))
              expl (delay (-class-explain this))]
          (reify
            SchemaSyntax
            (original-syntax [_] this)
            Schema
            (spec [_] @sp)
            (explain [_] @expl))))))

(extend-protocol Schema
  #?(:clj Class
     :cljs function)
  (spec [this] (-> this -class-schema spec))
  (explain [this] (-> this -class-schema explain))
  #?@(:bb [sci.lang.Type
           (spec [this] (-> this -class-schema spec))
           (explain [this] (-> this -class-schema explain))]))

;; On the JVM, the primitive coercion functions (double, long, etc)
;; alias to the corresponding boxed number classes

#?(:clj
(do
  (defmacro extend-primitive [cast-sym class-sym]
    (let [qualified-cast-sym `(class @(resolve '~cast-sym))]
      `(let [spec# (delay (variant/variant-spec spec/+no-precondition+ [{:schema ~class-sym}]))]
         (extend-protocol Schema
           ~qualified-cast-sym
           (spec [_#] @spec#)
           (explain [_#] '~cast-sym)))))

  (extend-primitive double Double)
  (extend-primitive float Float)
  (extend-primitive long Long)
  (extend-primitive int Integer)
  (extend-primitive short Short)
  (extend-primitive char Character)
  (extend-primitive byte Byte)
  (extend-primitive boolean Boolean)

  (extend-primitive doubles (Class/forName "[D"))
  (extend-primitive floats (Class/forName "[F"))
  (extend-primitive longs (Class/forName "[J"))
  (extend-primitive ints (Class/forName "[I"))
  (extend-primitive shorts (Class/forName "[S"))
  (extend-primitive chars (Class/forName "[C"))
  (extend-primitive bytes (Class/forName "[B"))
  (extend-primitive booleans (Class/forName "[Z"))))

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
        :cljs [->id (cc/fn [cls-name field]
                      (str "schema$core$" cls-nme "$" (name field)))])
  (defn- -set-cached-record-field-fn [this cls-name field this->v]
    (let [f (let [d (delay (this->v this))]
              (fn [this']
                (when (identical? this this')
                  @d)))
          _ #?(:clj (let [^java.util.Map weak (field->Map field)]
                      (.put weak this f)
                      f)
               :cljs (gobject/set this (->id cls-nme field) f))]
      f))

  (defn- -get-cached-record-field [this cls-nme field this->v]
    (or (when-some [f #?(:clj (let [^java.util.Map weak (field->Map field)]
                                (.get weak this))
                         :cljs (gobject/get this (->id cls-nme field)))]
          (f this))
        ((-set-cached-record-field-fn
           this cls-nme field this->v)
         this))))

#?(:clj
   (defmacro ^:private defrecord-cached-schema
     [n fs this->spec this->explain & args]
     (assert (symbol? this->spec))
     (assert (symbol? this->explain))
     `(macros/defrecord-schema ~n ~fs
        Schema
        (~'spec [this#] (-get-cached-record-field this# '~n :spec ~this->spec))
        (~'explain [this#] (-get-cached-record-field this# '~n :explain ~this->explain))
        ~@args)))

(defn- -construct-cached-schema-record [this cls-name this->spec this->explain]
  (doto this
    (-set-cached-record-field-fn cls-name :spec this->spec)
    (-set-cached-record-field-fn cls-name :explain this->explain)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Cross-platform Schema leaves

;;; Any matches anything (including nil)

(def ^:private -Anything-spec (delay (leaf/leaf-spec spec/+no-precondition+ any?)))

(macros/defrecord-schema AnythingSchema [_]
  ;; _ is to work around bug in Clojure where eval-ing defrecord with no fields
  ;; loses type info, which makes this unusable in schema-fn.
  ;; http://dev.clojure.org/jira/browse/CLJ-1093
  Schema
  (spec [this] @-Anything-spec)
  (explain [this] 'Any))

(def Any
  "Any value, including nil."
  (AnythingSchema. nil))


;;; eq (to a single allowed value)

(defn- -Eq-explain [^EqSchema this]
  (list 'eq (.-v this)))

(defn- -Eq-spec [^EqSchema this]
  (let [v (.-v this)]
    (leaf/leaf-spec (spec/precondition this #(= v %) #(list '= v %)))))

(defrecord-cached-schema EqSchema [v]
  -Eq-spec
  -Eq-explain)

(clojure.core/defn eq
  "A value that must be (= v)."
  [v]
  (-construct-cached-schema-record
    'EqSchema (EqSchema. v) -Eq-spec -Eq-explain))


;;; isa (a child of parent)

(defn- -Isa-spec [^Isa this]
  (let [h (.-h this)
        parent (.-parent this)]
    (leaf/leaf-spec (spec/precondition this
                                       (if h
                                         #(isa? h % parent)
                                         #(isa? % parent))
                                       #(list 'isa? % parent)))))

(defn- -Isa-explain [^Isa this]
  (list 'isa? (.-parent this)))

(defrecord-cached-schema Isa [h parent]
  -Isa-spec
  -Isa-explain)

(clojure.core/defn isa
  "A value that must be a child of parent."
  ([parent]
     (isa nil parent))
  ([h parent]
     (-> (Isa. h parent)
         (-construct-cached-schema-record
           'Isa -Isa-spec -Isa-explain))))


;;; enum (in a set of allowed values)

(defn- -Enum-spec [^EnumSchema this]
  (let [vs (.-vs this)
        pred #(contains? vs %)]
    (leaf/leaf-spec (spec/precondition this pred #(list vs %))
                    pred)))

(defn- -Enum-explain [^EnumSchema this]
  (cons 'enum (or (::original-vs this)
                  (.-vs this))))

(defrecord-cached-schema EnumSchema [vs]
  -Enum-spec
  -Enum-explain)

(clojure.core/defn enum
  "A value that must be = to some element of vs."
  [& vs]
  (-> (assoc (EnumSchema. (set vs)) ::original-vs vs)
      (-construct-cached-schema-record
        'Enum -Enum-spec -Enum-explain)))



;;; pred (matches all values for which p? returns truthy)

(defn- -Predicate-spec [^Predicate this]
  (let [p? (.-p? this)
        pred-name (.-pred-name this)]
    (leaf/leaf-spec (spec/precondition this p? #(list pred-name %)))))

(defn- -Predicate-explain [^Predicate this]
  (let [p? (.-p? this)
        pred-name (.-pred-name this)]
    (cond (= p? integer?) 'Int
          (= p? keyword?) 'Keyword
          (= p? symbol?) 'Symbol
          (= p? string?) 'Str
          :else (list 'pred pred-name))))

(defrecord-cached-schema Predicate [p? pred-name]
  -Predicate-spec
  -Predicate-explain)

(clojure.core/defn pred
  "A value for which p? returns true (and does not throw).
   Optional pred-name can be passed for nicer validation errors."
  ([p?] (pred p? (symbol (utils/fn-name p?))))
  ([p? pred-name]
     (when-not (ifn? p?)
       (macros/error! (utils/format* "Not a function: %s" p?)))
     (-> (Predicate. p? pred-name) 
         (-construct-cached-schema-record
           'Predicate -Predicate-spec -Predicate-explain))))


;;; protocol (which value must `satisfies?`)

(clojure.core/defn protocol-name [protocol]
  (-> protocol meta :proto-sym))

(defn- -Protocol-spec [^Protocol this]
  (let [p (.-p this)]
    (leaf/leaf-spec
      (spec/precondition
        this
        (:proto-pred (meta this))
        #(list 'satisfies? (protocol-name this) %)))))

(defn- -Protocol-explain [this]
  (list 'protocol (protocol-name this)))

;; In cljs, satisfies? is a macro so we must precompile (partial satisfies? p)
;; and put it in metadata of the record so that equality is preserved, along with the name.
(defrecord-cached-schema Protocol [p]
  -Protocol-spec
  -Protocol-explain)

;; The cljs version is macros/protocol by necessity, since cljs `satisfies?` is a macro.
#?(:clj
(defmacro protocol
  "A value that must satisfy? protocol p.

   Internally, we must make sure not to capture the value of the protocol at
   schema creation time, since that's impossible in cljs and breaks later
   extends in Clojure.

   A macro for cljs sake, since `satisfies?` is a macro in cljs."
  [p]
  `(-> (with-meta (->Protocol ~p)
                  {:proto-pred #(satisfies? ~p %)
                   :proto-sym '~p})
       (-construct-cached-schema-record
         '~'Protocol -Protocol-spec -Protocol-explain))))


;;; regex (validates matching Strings)

(defn- -re-schema [this]
  (or (utils/get-syntax-schema this)
      (utils/declare-syntax-schema!
        this
        (let [sp (delay (leaf/leaf-spec
                          (some-fn
                            (spec/simple-precondition this string?)
                            (spec/precondition this #(re-find this %) #(list 're-find (explain this) %)))))
              expl (delay
                     #?(:clj (symbol (str "#\"" this "\""))
                        :cljs (symbol (str "#\"" (.slice (str this) 1 -1) "\""))))]
          (reify
            SchemaSyntax
            (original-syntax [_] this)
            Schema
            (spec [_] @sp)
            (explain [_] @this))))))

(extend-protocol Schema
  #?(:clj java.util.regex.Pattern
     :cljs js/RegExp)
  (spec [this] (-> this -re-schema spec))
  (explain [this] (-> this -re-schema explain)))


;;; Cross-platform Schemas for atomic value types

(def Str
  "Satisfied only by String.
   Is (pred string?) and not js/String in cljs because of keywords."
  #?(:clj (doto java.lang.String
            (utils/register-class-pred! string?))
     :cljs (pred string? 'string?)))

(def Bool
  "Boolean true or false"
  #?(:clj (doto java.lang.Boolean
            (utils/register-class-pred! #(instance? Boolean %)))
     :cljs js/Boolean))

(def Num
  "Any number"
  #?(:clj (doto java.lang.Number
            (utils/register-class-pred! number?))
     :cljs js/Number))

(def Int
  "Any integral number"
  (pred integer? 'integer?))

(def Keyword
  "A keyword"
  (pred keyword? 'keyword?))

(def Symbol
  "A symbol"
  (pred symbol? 'symbol?))

(def Regex
  "A regular expression"
  #?(:clj (doto java.util.regex.Pattern
            (utils/register-class-pred! #(instance? java.util.regex.Pattern %)))
     :cljs (reify Schema ;; Closure doesn't like if you just def as js/RegExp
             (spec [this]
               (leaf/leaf-spec
                 (spec/precondition this #(instance? js/RegExp %) #(list 'instance? 'js/RegExp %))))
             (explain [this] 'Regex))))

(def Inst
  "The local representation of #inst ..."
  #?(:clj (doto java.util.Date
            (utils/register-class-pred! #(instance? java.util.Date %)))
     :cljs js/Date))

(def Uuid
  "The local representation of #uuid ..."
  #?(:clj (doto java.util.UUID
            (utils/register-class-pred! #(instance? java.util.UUID %)))
     :cljs cljs.core/UUID))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Variant schemas (and other unit containers)

;;; maybe (nil)

(let [nil-option (delay {:guard nil? :schema nil-schema})]
  (defn- -Maybe-spec [^Maybe this]
    (let [schema (.-schema this)]
      (variant/variant-spec
        spec/+no-precondition+
        [@nil-option
         {:schema schema}]))))

(defn- -Maybe-explain [^Maybe this]
  (let [schema (.-schema this)]
    (list 'maybe (explain schema))))

(defrecord-cached-schema Maybe [schema]
  -Maybe-spec
  -Maybe-explain)

(clojure.core/defn maybe
  "A value that must either be nil or satisfy schema"
  [schema]
  (-> (Maybe. schema)
      (-construct-cached-schema-record
        'Maybe -Maybe-spec -Maybe-explain)))

;;; named (schema elements)

(defn- -Named-spec [^NamedSchema this]
  (let [schema (.-schema this)
        name (.-name this)]
    (variant/variant-spec
      spec/+no-precondition+
      [{:schema schema :wrap-error #(utils/->NamedError name %)}])))

(defn- -Named-explain [^NamedSchema this]
  (let [schema (.-schema this)
        name (.-name this)]
    (list 'named (explain schema) name)))

(defrecord-cached-schema NamedSchema [schema name]
  -Named-spec
  -Named-explain)

(clojure.core/defn named
  "A value that must satisfy schema, and has a name for documentation purposes."
  [schema name]
  (-> (NamedSchema. schema name)
      (-construct-cached-schema-record
        'NamedSchema -Named-spec -Named-explain)))


;;; either (satisfies this schema or that one)

(defn- -Either-spec [^Either this]
  (let [schemas (.-schemas this)]
    (variant/variant-spec
      spec/+no-precondition+
      (for [s schemas]
        {:guard (complement (checker s)) ;; since the guard determines which option we check against
         :schema s})
      #(list 'some-matching-either-clause? %))))

(defn- -Either-explain [^Either this]
  (let [schemas (.-schemas this)]
    (cons 'either (map explain schemas))))

(defrecord-cached-schema Either [schemas]
  -Either-spec
  -Either-explain)

(clojure.core/defn ^{:deprecated "1.0.0"} either
  "A value that must satisfy at least one schema in schemas.
   Note that `either` does not work properly with coercion

   DEPRECATED: prefer `conditional` or `cond-pre`

   WARNING: either does not work with coercion.  It is also slow and gives
   bad error messages.  Please consider using `conditional` and friends
   instead; they are more efficient, provide better error messages,
   and work with coercion."
  [& schemas]
  (-> (Either. schemas)
      (-construct-cached-schema-record
        'Either -Either-spec -Either-explain)))


;;; conditional (choice of schema, based on predicates on the value)

(defn- -Conditional-spec [preds-and-schemas error-symbol]
  (variant/variant-spec
    spec/+no-precondition+
    (for [[p s] preds-and-schemas]
      {:guard p :schema s})
    #(list (or error-symbol
               (if (= 1 (count preds-and-schemas))
                 (symbol (utils/fn-name (ffirst preds-and-schemas)))
                 'some-matching-condition?))
           %)))

(defn- -Conditional-explain [preds-and-schemas error-symbol]
  (cons 'conditional
        (concat
          (mapcat (clojure.core/fn [[pred schema]] [(symbol (utils/fn-name pred)) (explain schema)])
                  preds-and-schemas)
          (when error-symbol [error-symbol]))))

(defrecord-cached-schema ConditionalSchema [preds-and-schemas error-symbol]
  -Conditional-spec
  -Conditional-explain)

(clojure.core/defn conditional
  "Define a conditional schema.  Takes args like cond,
   (conditional pred1 schema1 pred2 schema2 ...),
   and checks the first schemaX where predX (an ordinary Clojure function
   that returns true or false) returns true on the value.
   Unlike cond, throws if the value does not match any condition.
   :else may be used as a final condition in the place of (constantly true).
   More efficient than either, since only one schema must be checked.
   An optional final argument can be passed, a symbol to appear in
   error messages when none of the conditions match."
  [& preds-and-schemas]
  (macros/assert!
   (and (seq preds-and-schemas)
        (or (even? (count preds-and-schemas))
            (symbol? (last preds-and-schemas))))
   "Expected even, nonzero number of args (with optional trailing symbol); got %s"
   (count preds-and-schemas))
  (-> (ConditionalSchema. (vec
                            (for [[pred schema] (partition 2 preds-and-schemas)]
                              (do (macros/assert! (ifn? pred) (str "Conditional predicate " pred " must be a function"))
                                  [(if (= pred :else) (constantly true) pred) schema])))
                          (if (odd? (count preds-and-schemas)) (last preds-and-schemas)))
      (-construct-cached-schema-record
        'Conditional -Conditional-spec -Conditional-explain)))


;; cond-pre (conditional based on surface type)

(clojure.core/defprotocol HasPrecondition
  (precondition [this]
    "Return a predicate representing the Precondition for this schema:
     the predicate returns true if the precondition is satisfied.
     (See spec.core for more details)"))

(extend-protocol HasPrecondition
  schema.spec.leaf.LeafSpec
  (precondition [this]
    (spec/pre-pred this nil))

  schema.spec.variant.VariantSpec
  (precondition [^schema.spec.variant.VariantSpec this]
    (let [pre-pred (spec/pre-pred this nil)
          f (reduce
              (cc/fn [f {:keys [guard schema]}]
                (let [p? (spec/pre-pred schema nil)
                      g (if guard
                          #(and (guard %) (p? %))
                          p?)]
                  #(or (g %) (f %))))
              never? (rseq (.-options this)))]
      #(and (pre-pred %)
            (f %))))

  schema.spec.collection.CollectionSpec
  (precondition [this]
    (spec/pre-pred this nil)))

(defn- -CondPre-spec [^CondPre this]
  (let [schemas (.-schemas this)]
    (variant/variant-spec
      spec/+no-precondition+
      (for [s schemas]
        {:guard (precondition (spec s))
         :schema s})
      #(list 'matches-some-precondition? %))))

(defn- -CondPre-explain [^CondPre this]
  (let [schemas (.-schemas this)]
    (cons 'cond-pre
          (map explain schemas))))

(defrecord-cached-schema CondPre [schemas]
  -CondPre-spec
  -CondPre-explain)

(clojure.core/defn cond-pre
  "A replacement for `either` that constructs a conditional schema
   based on the schema spec preconditions of the component schemas.

   Given a datum, the preconditions for each schema (which typically
   check just the outermost class) are tested against the datum in turn.
   The first schema whose precondition matches is greedily selected,
   and the datum is validated against that schema.  Unlike `either`,
   a validation failure is final (and there is no backtracking to try
   other schemas that might match).

   Thus, `cond-pre` is only suitable for schemas with mutually exclusive
   preconditions (e.g., s/Int and s/Str).  If this doesn't hold
   (e.g. {:a s/Int} and {:b s/Str}), you must use `conditional` instead
   and provide an explicit condition for distinguishing the cases.

   EXPERIMENTAL"
  [& schemas]
  (-> (CondPre. schemas)
      (-construct-cached-schema-record
        -CondPre-spec
        -CondPre-explain)))

;; constrained (post-condition on schema)

(macros/defrecord-schema Constrained [schema postcondition post-name]
  Schema
  (spec [this]
    (variant/variant-spec
     {:pre spec/+no-precondition+
      :options [{:schema schema}]
      :post (spec/precondition this postcondition #(list post-name %))
      :params->pred (cc/fn [params]
                      (let [pred (spec/pred (spec schema) params)]
                        #(and (pred %) (postcondition %))))
      :params->pre-pred (cc/fn [_] any?)}))
  (explain [this]
    (list 'constrained (explain schema) post-name)))

(clojure.core/defn constrained
  "A schema with an additional post-condition.  Differs from `conditional`
   with a single schema, in that the predicate checked *after* the main
   schema.  This can lead to better error messages, and is often better
   suited for coercion."
  ([s p?] (constrained s p? (symbol (utils/fn-name p?))))
  ([s p? pred-name]
     (when-not (ifn? p?)
       (macros/error! (utils/format* "Not a function: %s" p?)))
     (Constrained. s p? pred-name)))

;;; both (satisfies this schema and that one)

(macros/defrecord-schema Both [schemas]
  Schema
  (spec [this] this)
  (explain [this] (cons 'both (map explain schemas)))
  HasPrecondition
  (precondition [this]
    (apply every-pred (map (comp precondition spec) schemas)))
  spec/CoreSpec
  (subschemas [this] schemas)
  (checker [this params]
    (reduce
     (clojure.core/fn [f t]
       (clojure.core/fn [x]
         (let [tx (t x)]
           (if (utils/error? tx)
             tx
             (f (or tx x))))))
     (map #(spec/sub-checker {:schema %} params) (rseq schemas))))
  spec/PredSpec
  (pred [this params]
    (reduce
     (cc/fn [f s]
       (let [p? (spec/pred (spec s) params)]
         #(and (p? %) (f %))))
     any? (rseq schemas)))
  spec/PrePredSpec
  (pre-pred [this params]
    (reduce
     (cc/fn [f s]
       (let [p? (spec/pre-pred (spec s) params)]
         #(and (p? %) (f %))))
     any? (rseq schemas))))

(clojure.core/defn ^{:deprecated "1.0.0"} both
  "A value that must satisfy every schema in schemas.

   DEPRECATED: prefer 'conditional' with a single condition
   instead, or `constrained`.

   When used with coercion, coerces each schema in sequence."
  [& schemas]
  (Both. (vec schemas)))


(clojure.core/defn if
  "if the predicate returns truthy, use the if-schema, otherwise use the else-schema"
  [pred if-schema else-schema]
  (conditional pred if-schema (constantly true) else-schema))


;;; Recursive schemas
;; Supports recursively defined schemas by using the level of indirection offered by by
;; Clojure and ClojureScript vars.

(clojure.core/defn var-name [v]
  (let [{:keys [ns name]} (meta v)]
    (symbol (str #?(:clj (ns-name ns)
                    :cljs ns)
                 "/" name))))

(macros/defrecord-schema Recursive [derefable]
  Schema
  (spec [this] (variant/variant-spec
                 {:pre spec/+no-precondition+
                  :options [{:schema @derefable}]
                  :params->pred #(spec/pred (spec @derefable) %)
                  :params->pre-pred #(spec/pre-pred (spec @derefable) %)}))
  (explain [this]
    (list 'recursive
          (if #?(:clj (var? derefable)
                 :cljs (instance? Var derefable))
            (list 'var (var-name derefable))
            #?(:clj
               (format "%s@%x"
                       (.getName (class derefable))
                       (System/identityHashCode derefable))
               :cljs
               '...)))))

(clojure.core/defn recursive
  "Support for (mutually) recursive schemas by passing a var that points to a schema,
   e.g (recursive #'ExampleRecursiveSchema)."
  [schema]
  (macros/assert! #?(:clj (instance? clojure.lang.IDeref schema)
                     :cljs (satisfies? IDeref schema))
                  "Not an IDeref: %s" schema)
  (Recursive. schema))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Atom schema

(defn- atom? [x]
  #?(:clj (instance? clojure.lang.Atom x)
     :cljs (satisfies? IAtom x)))

(macros/defrecord-schema Atomic [schema]
  Schema
  (spec [this]
    (collection/collection-spec
      {:pre (spec/simple-precondition this atom?)
       :konstructor clojure.core/atom
       :elements
       [(collection/one-element true schema (clojure.core/fn [item-fn coll] (item-fn @coll) nil))]
       :on-error
       (clojure.core/fn [_ xs _] (clojure.core/atom (first xs)))
       :params->pred (cc/fn [params]
                       (let [p (spec/pred (spec schema) params)]
                         #(and (atom? %) (p %))))
       :params->pre-pred (cc/fn [_] atom?)}))
  (explain [this] (list 'atom (explain schema))))

(clojure.core/defn atom
  "An atom containing a value matching 'schema'."
  [schema]
  (->Atomic schema))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Map Schemas

;; A map schema is itself a Clojure map, which can provide value schemas for specific required
;; and optional keys, as well as a single, optional schema for additional key-value pairs.

;; Specific keys are mapped to value schemas, and given as either:
;;  - (required-key k), a required key (= k)
;;  - a keyword, also a required key
;;  - (optional-key k), an optional key (= k)
;; For example, {:a Int (optional-key :b) String} describes a map with key :a mapping to an
;; integer, an optional key :b mapping to a String, and no other keys.

;; There can also be a single additional key, itself a schema, mapped to the schema for
;; corresponding values, which applies to all key-value pairs not covered by an explicit
;; key.
;; For example, {Int String} is a mapping from integers to strings, and
;; {:a Int Int String} is a mapping from :a to an integer, plus zero or more additional
;; mappings from integers to strings.


;;; Definitions for required and optional keys, and single entry validators

(clojure.core/defrecord RequiredKey [k])

(clojure.core/defn required-key
  "A required key in a map"
  [k]
  (if (keyword? k)
    k
    (RequiredKey. k)))

(clojure.core/defn required-key? [ks]
  (or (keyword? ks)
      (instance? RequiredKey ks)))

(clojure.core/defrecord OptionalKey [k])

(clojure.core/defn optional-key
  "An optional key in a map"
  [k]
  (OptionalKey. k))

(clojure.core/defn optional-key? [ks]
  (instance? OptionalKey ks))


(clojure.core/defn explicit-schema-key [ks]
  (cond (keyword? ks) ks
        (instance? RequiredKey ks) (.-k ^RequiredKey ks)
        (optional-key? ks) (.-k ^OptionalKey ks)
        :else (macros/error! (utils/format* "Bad explicit key: %s" ks))))

(clojure.core/defn specific-key? [ks]
  (or (required-key? ks)
      (optional-key? ks)))

(clojure.core/defn map-entry-ctor [[k v :as coll]]
  #?(:clj (clojure.lang.MapEntry. k v)
     :cljs (cljs.core.MapEntry. k v nil)))

(defn- -map-entry-spec [key-schema val-schema]
  (collection/collection-spec
    spec/+no-precondition+
    map-entry-ctor
    [(collection/one-element true key-schema (clojure.core/fn [item-fn e] (item-fn (key e)) e))
     (collection/one-element true val-schema (clojure.core/fn [item-fn e] (item-fn (val e)) nil))]
    (clojure.core/fn [[k] [xk xv] _]
      (if-let [k-err (utils/error-val xk)]
        [k-err 'invalid-key]
        [k (utils/error-val xv)]))))

(defn- -map-entry-explain [key-schema val-schema]
  (list 'map-entry? (explain key-schema) (explain val-schema)))

;; A schema for a single map entry.
(macros/defrecord-schema MapEntry [key-schema val-schema]
  Schema
  (spec [this] (or (force (::spec this))
                   (-map-entry-spec key-schema val-schema)))
  (explain [this] (or (force (::explain this))
                      (-map-entry-explain key-schema val-schema))))

(clojure.core/defn map-entry [key-schema val-schema]
  (let [this (MapEntry. key-schema val-schema)]
    (assoc this
           ::spec (delay (-map-entry-spec key-schema val-schema))
           ::explain (delay (-map-entry-explain key-schema val-schema)))))

(clojure.core/defn find-extra-keys-schema [map-schema]
  (let [key-schemata (remove specific-key? (keys map-schema))]
    (macros/assert! (< (count key-schemata) 2)
                    "More than one non-optional/required key schemata: %s"
                    (vec key-schemata))
    (first key-schemata)))

(clojure.core/defn- explain-kspec [kspec]
  (if (specific-key? kspec)
    (if (keyword? kspec)
      kspec
      (list (cond (required-key? kspec) 'required-key
                  (optional-key? kspec) 'optional-key)
            (explicit-schema-key kspec)))
    (explain kspec)))

(defn- map-elements [this]
  (let [extra-keys-schema (find-extra-keys-schema this)]
    (let [duplicate-keys (->> (dissoc this extra-keys-schema)
                              keys
                              (group-by explicit-schema-key)
                              vals
                              (filter #(> (count %) 1))
                              (apply concat)
                              (mapv explain-kspec))]
      (macros/assert! (empty? duplicate-keys)
                      "Schema has multiple variants of the same explicit key: %s" duplicate-keys))
    (let [without-extra-keys-schema (dissoc this extra-keys-schema)]
      (concat
       (for [[k v] without-extra-keys-schema]
         (let [rk (explicit-schema-key k)
               required? (required-key? k)]
           (collection/one-element
            required? (map-entry (eq rk) v)
            (clojure.core/fn [item-fn m]
              (let [e (find m rk)]
                (cond e (item-fn e)
                      required? (item-fn (utils/error [rk 'missing-required-key])))
                (if e
                  (dissoc #?(:clj (if (instance? clojure.lang.PersistentStructMap m) (into {} m) m)
                             :cljs m)
                          rk)
                  m))))))
       (when extra-keys-schema
         (let [specific-keys (into #{} (map explicit-schema-key) (keys without-extra-keys-schema))
               [ks vs] (find this extra-keys-schema)
               restricted-ks (constrained ks #(not (contains? specific-keys %)))]
           [(collection/all-elements (map-entry restricted-ks vs))]))))))

(defn- map-error []
  (clojure.core/fn [_ elts extra]
    (-> {}
        (into (keep utils/error-val) elts)
        (into (map (cc/fn [[k]] [k 'disallowed-key])) extra))))

(defn- map-spec [this]
  (collection/collection-spec
    (spec/simple-precondition this map?)
    #(into {} %)
    (map-elements this)
    (map-error)))

(clojure.core/defn- map-explain [this]
  (reduce-kv (cc/fn [m k v]
               (assoc m (explain-kspec k) (explain v)))
             {} this))

(defn- -map-schema [this]
  (or (utils/get-syntax-schema this)
      (utils/declare-syntax-schema!
        this
        (let [sp (delay (map-spec this))
              expl (delay (map-explain this))]
          (reify
            SchemaSyntax
            (original-syntax [_] this)
            Schema
            (spec [_] @sp)
            (explain [_] @this))))))

(extend-protocol Schema
  #?(:clj clojure.lang.APersistentMap
     :cljs cljs.core.PersistentArrayMap)
  (spec [this] (-> this -map-schema spec))
  (explain [this] (-> this -map-schema explain))
  #?(:cljs cljs.core.PersistentHashMap)
  #?(:cljs (spec [this] (-> this -map-schema spec)))
  #?(:cljs (explain [this] (-> this -map-schema explain))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Set schemas

;; A set schema is a Clojure set with a single element, a schema that all values must satisfy

(defn- -set-schema [this]
  (macros/assert! (= (count this) 1) "Set schema must have exactly one element")
  (or (utils/get-syntax-schema this)
      (utils/declare-syntax-schema!
        this
        (let [sp (delay (collection/collection-spec
                          {:pre (spec/simple-precondition this set?)
                           :konstructor set
                           :options [(collection/all-elements (first this))]
                           :on-error (clojure.core/fn [_ xs _] (set (keep utils/error-val xs)))
                           :params->pred (cc/fn [params]
                                           (let [p (spec/pred (spec (first this)) params)]
                                             #(and (set? %) (p %))))
                           :params->pre-pred (cc/fn [_] set?)}))
              expl (delay #{(explain (first this))})]
          (reify
            SchemaSyntax
            (original-syntax [_] this)
            Schema
            (spec [_] @sp)
            (explain [_] @this))))))

(extend-protocol Schema
  #?(:clj clojure.lang.APersistentSet
     :cljs cljs.core.PersistentHashSet)
  (spec [this] (-> this -set-schema spec))
  (explain [this] (-> this -set-schema explain)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queue schemas

;; A queue schema is satisfied by PersistentQueues containing values that all satisfy
;; a specific sub-schema.

(clojure.core/defn queue? [x]
  (instance?
    #?(:clj clojure.lang.PersistentQueue
       :cljs cljs.core/PersistentQueue)
   x))

(def ^:private empty-queue
  #?(:clj clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

(clojure.core/defn as-queue [col]
  (reduce
   conj
   #?(:clj clojure.lang.PersistentQueue/EMPTY
      :cljs cljs.core/PersistentQueue.EMPTY)
   col))

(macros/defrecord-schema Queue [schema]
  Schema
  (spec [this]
    (collection/collection-spec
     {:pre (spec/simple-precondition this queue?)
      :konstructor as-queue
      :options [(collection/all-elements schema)]
      :on-error (clojure.core/fn [_ xs _] (into empty-queue (keep utils/error-val) xs))
      :params->pred (cc/fn [params]
                      (let [p (spec/pred (spec schema) params)]
                        #(and (queue? %) (p %))))
      :params->pre-pred (cc/fn [params]
                          (let [p (spec/pre-pred (spec schema) params)]
                            #(and (queue? %) (p %))))}))
  (explain [this] (list 'queue (explain schema))))

(clojure.core/defn queue
  "Defines a schema satisfied by instances of clojure.lang.PersistentQueue
  (clj.core/PersistentQueue in ClojureScript) whose values satisfy x."
  [x]
  (Queue. x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sequence Schemas

;; A sequence schema looks like [one* optional* rest-schema?].
;; one matches a single required element, and must be the output of 'one' below.
;; optional matches a single optional element, and must be the output of 'optional' below.
;; Finally, rest-schema is any schema, which must match any remaining elements.
;; (if optional elements are present, they must be matched before the rest-schema is applied).

(clojure.core/defrecord One [schema optional? name])

(clojure.core/defn one
  "A single required element of a sequence (not repeated, the implicit default)"
  ([schema name]
     (One. schema false name)))

(clojure.core/defn optional
  "A single optional element of a sequence (not repeated, the implicit default)"
  ([schema name]
     (One. schema true name)))

(clojure.core/defn parse-sequence-schema
  "Parses and validates a sequence schema, returning a vector in the form
  [singles multi] where singles is a sequence of 'one' and 'optional' schemas
  and multi is the rest-schema (which may be nil). A valid sequence schema is
  a vector in the form [one* optional* rest-schema?]."
  [s]
  (let [[required more] (split-with #(and (instance? One %) (not (:optional? %))) s)
        [optional more] (split-with #(and (instance? One %) (:optional? %)) more)]
    (macros/assert!
     (and (<= (count more) 1) (not-any? #(instance? One %) more))
     "%s is not a valid sequence schema; %s%s%s" s
     "a valid sequence schema consists of zero or more `one` elements, "
     "followed by zero or more `optional` elements, followed by an optional "
     "schema that will match the remaining elements.")
    [(concat required optional) (first more)]))

;;TODO rename to -sequence-schema-pre-pred
(defn- sequential-schema-pre-pred [x]
  (or (nil? x) (sequential? x) #?(:clj (instance? java.util.List x))))

(defn- -sequence-spec [this]
  (collection/collection-spec
    {:pre
     (spec/precondition
       this
       sequential-schema-pre-pred
       #(list 'sequential? %))
     :konstructor
     vec
     :options
     (let [[singles multi] (parse-sequence-schema this)]
       (reduce
         (clojure.core/fn [more ^One s]
           (if-not (.-optional? s)
             (cons
               (collection/one-element
                 true (named (.-schema s) (.-name s))
                 (clojure.core/fn [item-fn x]
                   (if-let [x (seq x)]
                     (do (item-fn (first x))
                         (rest x))
                     (do (item-fn
                           (macros/validation-error
                             (.-schema s) ::missing
                             (list 'present? (.-name s))))
                         nil))))
               more)
             [(collection/optional-tail
                (named (.-schema s) (.-name s))
                (clojure.core/fn [item-fn x]
                  (when-let [x (seq x)]
                    (item-fn (first x))
                    (rest x)))
                more)]))
         (when multi
           [(collection/all-elements multi)])
         (reverse singles)))
     :on-error
     (clojure.core/fn [_ elts extra]
       (let [head (mapv utils/error-val elts)]
         (cond-> head
           (seq extra) (conj (utils/error-val (macros/validation-error nil extra (list 'has-extra-elts? (count extra))))))))
     :params->pred (cc/fn [_] (assert nil 'TODO))
     :params->pre-pred (cc/fn [_] sequential-schema-pre-pred)}))

(defn- -sequence-explain [this]
  (let [[singles multi] (parse-sequence-schema this)]
    (cond-> (mapv (clojure.core/fn [^One s]
                    (list (if (.-optional? s) 'optional 'one) (explain (:schema s)) (:name s)))
                  singles)
      multi (conj (explain multi)))))

(defn- -sequence-schema [this]
  (or (utils/get-syntax-schema this)
      (utils/declare-syntax-schema!
        this
        (let [sp (delay (-sequence-spec this))
              expl (delay (-sequence-explain this))]
          (reify
            SchemaSyntax
            (original-syntax [_] this)
            Schema
            (spec [_] @sp)
            (explain [_] @this))))))

(extend-protocol Schema
  #?(:clj clojure.lang.APersistentVector
     :cljs cljs.core.PersistentVector)
  (spec [this] (-> this -sequence-schema spec))
  (explain [this] (-> this -sequence-schema explain)))

(clojure.core/defn pair
  "A schema for a pair of schemas and their names"
  [first-schema first-name second-schema second-name]
  [(one first-schema first-name)
   (one second-schema second-name)])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Record Schemas

;; A Record schema describes a value that must have the correct type, and its body must
;; also satisfy a map schema.  An optional :extra-validator-fn can also be attached to do
;; additional validation.

(macros/defrecord-schema Record [klass schema]
  Schema
  (spec [{:keys [extra-validator-fn] :as this}]
    (let [pre-pred (instance-pred klass)]
      (collection/collection-spec
        {:pre
         (let [pre (spec/precondition this pre-pred #(list 'instance? klass %))]
           (if-some [extra (when extra-validator-fn
                             (spec/precondition this extra-validator-fn #(list 'passes-extra-validation? %)))]
             #(or (pre %) (extra %))
             pre))
         :konstructor
         (:konstructor (meta this))
         :options
         (map-elements schema)
         :on-error
         (map-error)
         :parent this
         :params->pred (cc/fn [_]
                         (if extra-validator-fn
                           #(and (pre-pred %) (extra-validator-fn %))
                           pre-pred))
         :params->pre-pred (cc/fn [_] pre-pred)})))
  (explain [this]
    (list 'record #?(:clj (or #?(:bb (when (instance? sci.lang.Type klass)
                                       (symbol (str klass))))
                              (symbol (.getName ^Class klass)))
                     :cljs (symbol (pr-str klass)))
          (explain schema))))

(clojure.core/defn record* [klass schema map-constructor]
  #?(:clj (macros/assert! (or (class? klass) #?(:bb (instance? sci.lang.Type klass))) "Expected record class, got %s" (utils/type-of klass)))
  (macros/assert! (map? schema) "Expected map, got %s" (utils/type-of schema))
  (with-meta (Record. klass schema) {:konstructor map-constructor}))

#?(:clj
(defmacro record
  "A Record instance of type klass, whose elements match map schema 'schema'.

   The final argument is the map constructor of the record type; if you do
   not pass one, an attempt is made to find the corresponding function
   (but this may fail in exotic circumstances)."
  ([klass schema]
   (let [map-ctor-var (let [bits (str/split (name klass) #"/")]
                        (symbol (str/join "/" (concat (butlast bits) [(str "map->" (last bits))]))))
         map-ctor-mth (symbol (str (name klass) "/create"))]
     `(record ~klass ~schema
              (macros/if-cljs
                ~map-ctor-var
                (macros/if-bb
                  ~map-ctor-var
                  #(~map-ctor-mth %))))))
  ([klass schema map-constructor]
     `(record* ~klass ~schema #(~map-constructor (into {} %))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Function Schemas

;; A function schema describes a function of one or more arities.
;; The function can only have a single output schema (across all arities), and each input
;; schema is a sequence schema describing the argument vector.

;; Currently function schemas are purely descriptive, and do not carry any validation logic.

(clojure.core/defn explain-input-schema [input-schema]
  (let [[required more] (split-with #(instance? One %) input-schema)]
    (concat (map #(explain (.-schema ^One %)) required)
            (when (seq more)
              ['& (mapv explain more)]))))

(macros/defrecord-schema FnSchema [output-schema input-schemas] ;; input-schemas sorted by arity
  Schema
  (spec [this] (leaf/leaf-spec (spec/simple-precondition this ifn?)
                               ifn?))
  (explain [this]
    (if (> (count input-schemas) 1)
      (list* '=>* (explain output-schema) (map explain-input-schema input-schemas))
      (list* '=> (explain output-schema) (explain-input-schema (first input-schemas))))))

(clojure.core/defn- arity [input-schema]
  (if (seq input-schema)
    (if (instance? One (last input-schema))
      (count input-schema)
      #?(:clj Long/MAX_VALUE
         :cljs js/Number.MAX_VALUE))
    0))

(clojure.core/defn make-fn-schema
  "A function outputting a value in output schema, whose argument vector must match one of
   input-schemas, each of which should be a sequence schema.
   Currently function schemas are purely descriptive; they validate against any function,
   regardless of actual input and output types."
  [output-schema input-schemas]
  (macros/assert! (seq input-schemas) "Function must have at least one input schema")
  (macros/assert! (every? vector? input-schemas) "Each arity must be a vector.")
  (macros/assert! (apply distinct? (map arity input-schemas)) "Arities must be distinct")
  (FnSchema. output-schema (sort-by arity input-schemas)))

#?(:clj
(defmacro =>*
  "Produce a function schema from an output schema and a list of arity input schema specs,
   each of which is a vector of argument schemas, ending with an optional '& more-schema'
   specification where more-schema must be a sequence schema.

   Currently function schemas are purely descriptive; there is no validation except for
   functions defined directly by s/fn or s/defn"
  [output-schema & arity-schema-specs]
  `(make-fn-schema ~output-schema ~(mapv macros/parse-arity-spec arity-schema-specs))))

#?(:clj
(defmacro =>
  "Convenience macro for defining function schemas with a single arity; like =>*, but
   there is no vector around the argument schemas for this arity."
  [output-schema & arg-schemas]
  `(=>* ~output-schema ~(vec arg-schemas))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers for defining schemas

(clojure.core/defn schema-with-name
  "Records name in schema's metadata."
  [schema name]
  (macros/assert! #?(:clj (instance? clojure.lang.IObj schema)
                     :cljs (satisfies? IWithMeta schema))
                  "Named schema (such as the right-most `s/defalias` arg) must support metadata: %s" (utils/type-of schema))
  (vary-meta schema assoc :name name))

(clojure.core/defn schema-name
  "Returns the name of a schema attached via schema-with-name (or defschema)."
  [schema]
  (-> schema meta :name))

(clojure.core/defn schema-ns
  "Returns the namespace of a schema attached via defschema."
  [schema]
  (-> schema meta :ns))

(cc/defn ^:internal -defschema [{s :schema :keys [name nsym]}]
  (let [name-schema #(vary-meta
                       (schema-with-name % name)
                       assoc :ns nsym)]
    (name-schema
      (if #?(:clj (instance? clojure.lang.IObj s)
             :cljs (satisfies? IWithMeta schema))
        (name-schema s)
        (or (utils/get-syntax-schema s)
            (utils/declare-syntax-schema!
              s
              (let [sp (delay (spec s))
                    expl (delay (explain s))]
                (reify
                  SchemaSyntax
                  (original-syntax [_] s)
                  Schema
                  (spec [_] @sp)
                  (explain [_] @expl)))))))))

#?(:clj
(defmacro defschema
  "Convenience macro to make it clear to the reader that body is meant to be used as a schema
   that also precomputes parts of the Schema for performance.

   The name of the schema is recorded in the metadata. If metadata is not supported on
   the schema, will be wrapped in a Schema in order to attach the metadata. The wrapper
   implements SchemaSyntax to recover the wrapped value."
  ([name form]
     `(defschema ~name "" ~form))
  ([name docstring form]
   `(def ~name ~docstring
      (-defschema
        {:schema ~form :name '~name :nsym '~(ns-name *ns*)})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schematized defrecord and (de,let)fn macros

#?(:clj
(defmacro defrecord
  "Define a record with a schema.

   In addition to the ordinary behavior of defrecord, this macro produces a schema
   for the Record, which will automatically be used when validating instances of
   the Record class:

   (m/defrecord FooBar
    [foo :- Int
     bar :- String])

   (schema.utils/class-schema FooBar)
   ==> (record user.FooBar {:foo Int, :bar java.lang.String})

   (s/check FooBar (FooBar. 1.2 :not-a-string))
   ==> {:foo (not (integer? 1.2)), :bar (not (instance? java.lang.String :not-a-string))}

   See (doc schema.core) for details of the :- syntax for record elements.

   Moreover, optional arguments extra-key-schema? and extra-validator-fn? can be
   passed to augment the record schema.
    - extra-key-schema is a map schema that defines validation for additional
      key-value pairs not in the record base (the default is to not allow extra
       mappings).
    - extra-validator-fn? is an additional predicate that will be used as part
      of validating the record value.

   The remaining opts+specs (i.e., protocol and interface implementations) are
   passed through directly to defrecord.

   Finally, this macro replaces Clojure's map->name constructor with one that is
   more than an order of magnitude faster (as of Clojure 1.5), and provides a
   new strict-map->name constructor that throws or drops extra keys not in the
   record base."
  {:arglists '([name field-schema extra-key-schema? extra-validator-fn? & opts+specs])}
  [name field-schema & more-args]
  (apply macros/emit-defrecord 'clojure.core/defrecord &env name field-schema more-args)))

#?(:clj
(defmacro defrecord+
  "DEPRECATED -- canonical version moved to schema.potemkin
   Like defrecord, but emits a record using potemkin/defrecord+.  You must provide
   your own dependency on potemkin to use this."
  {:arglists '([name field-schema extra-key-schema? extra-validator-fn? & opts+specs])}
  [name field-schema & more-args]
  (apply macros/emit-defrecord 'potemkin/defrecord+ &env name field-schema more-args)))

#?(:clj
(defmacro set-compile-fn-validation!
  [on?]
  (macros/set-compile-fn-validation! on?)
  nil))

(clojure.core/defn fn-validation?
  "Get the current global schema validation setting."
  []
  #?(:bb @utils/use-fn-validation
     :clj (.get ^java.util.concurrent.atomic.AtomicReference utils/use-fn-validation)
     :cljs @utils/use-fn-validation))

(clojure.core/defn set-fn-validation!
  "Globally turn on (or off) schema validation for all s/fn and s/defn instances."
  [on?]
  #?(:bb (reset! utils/use-fn-validation on?)
     :clj (.set ^java.util.concurrent.atomic.AtomicReference utils/use-fn-validation on?)
     :cljs (reset! utils/use-fn-validation on?)))

#?(:clj
(defmacro with-fn-validation
  "Execute body with input and output schema validation turned on for
   all s/defn and s/fn instances globally (across all threads). After
   all forms have been executed, resets function validation to its
   previously set value. Not concurrency-safe."
  [& body]
  `(let [body# (fn [] ~@body)]
     (if (fn-validation?)
       (body#)
       (do
         (set-fn-validation! true)
         (try (body#) (finally (set-fn-validation! false))))))))

#?(:clj
(defmacro without-fn-validation
  "Execute body with input and output schema validation turned off for
   all s/defn and s/fn instances globally (across all threads). After
   all forms have been executed, resets function validation to its
   previously set value. Not concurrency-safe."
  [& body]
  `(let [body# (fn [] ~@body)]
     (if (fn-validation?)
       (do
         (set-fn-validation! false)
         (try (body#) (finally (set-fn-validation! true))))
       (body#)))))

(def fn-validator
  "A var that can be rebound to a function to customize the behavior
  of fn validation. When fn validation is on and `fn-validator` is
  bound to a function, normal argument and return value checks will
  be substituted with a call to this function with five arguments:

    direction   - :input or :output
    fn-name     - a symbol, the function's name
    schema      - the schema for the arglist or the return value
    checker     - a precompiled checker to check a value against
                  the schema
    value       - the actual arglist or return value

  The function's return value will be ignored."
  nil)

(clojure.core/defn schematize-fn
  "Attach the schema to fn f at runtime, extractable by fn-schema."
  [f schema]
  (vary-meta f assoc :schema schema))

(clojure.core/defn ^FnSchema fn-schema
  "Produce the schema for a function defined with s/fn or s/defn."
  [f]
  ;; protocol methods in bb are multimethods
  (macros/assert! (or (fn? f) #?@(:bb [(instance? clojure.lang.MultiFn f)])) "Non-function %s" (utils/type-of f))
  (or (utils/class-schema (utils/fn-schema-bearer f))
      (macros/safe-get (meta f) :schema)))

#?(:clj
(defmacro fn
  "s/fn : s/defn :: clojure.core/fn : clojure.core/defn

   See (doc s/defn) for details.

   Additional gotchas and limitations:
    - Like s/defn, the output schema must go on the fn name. If you
      don't supply a name, schema will gensym one for you and attach
      the schema.
    - Unlike s/defn, the function schema is stored in metadata on the
      fn. The implications of this differ per platform:
      :clj   The resulting function has the same performance characteristics
             as clojure.core/fn. Additionally, the following invariant
             holds for all parameters and schema annotations:
               (let [f (s/fn this ... [...] this)]
                 (assert (identical? f (f ...))))
      :cljs  Returns a wrapper function that forwards arguments positionally
             up to 20 arguments, and then via `apply` beyond 20 arguments.
             See `cljs.core/with-meta` and `cljs.core.MetaFn`."
  [& fn-args]
  (let [fn-args (if (symbol? (first fn-args))
                  fn-args
                  (cons (gensym "fn") fn-args))
        [name more-fn-args] (macros/extract-arrow-schematized-element &env fn-args)
        {:keys [outer-bindings schema-form fn-body]} (macros/process-fn- &env name more-fn-args)]
    `(let [~@outer-bindings
           ;; let bind to work around https://clojure.atlassian.net/browse/CLJS-968
           f# ~(vary-meta `(clojure.core/fn ~name ~@fn-body)
                          #(assoc (merge (meta &form) %)
                                  :schema schema-form))]
       f#))))

#?(:clj
(defmacro defn
  "Like clojure.core/defn, except that schema-style typehints can be given on
   the argument symbols and on the function name (for the return value).

   You can call s/fn-schema on the defined function to get its schema back, or
   use with-fn-validation to enable runtime checking of function inputs and
   outputs.

   (s/defn foo :- s/Num
    [x :- s/Int
     y :- s/Num]
    (* x y))

   (s/fn-schema foo)
   ==> (=> java.lang.Number Int java.lang.Number)

   (s/with-fn-validation (foo 1 2))
   ==> 2

   (s/with-fn-validation (foo 1.5 2))
   ==> Input to foo does not match schema: [(named (not (integer? 1.5)) x) nil]

   See (doc schema.core) for details of the :- syntax for arguments and return
   schemas.

   The overhead for checking if run-time validation should be used is very
   small -- about 5% of a very small fn call.  On top of that, actual
   validation costs what it costs.

   You can also turn on validation unconditionally for this fn only by
   putting ^:always-validate metadata on the fn name.

   Gotchas and limitations:
    - The output schema always goes on the fn name, not the arg vector. This
      means that all arities must share the same output schema. Schema will
      automatically propagate primitive hints to the arg vector and class hints
      to the fn name, so that you get the behavior you expect from Clojure.
    - All primitive schemas will be passed through as type hints to Clojure,
      despite their legality in a particular position.  E.g.,
        (s/defn foo [x :- int])
      will fail because Clojure does not allow primitive ints as fn arguments;
      in such cases, use the boxed Classes instead (e.g., Integer).
    - Schema metadata is only processed on top-level arguments.  I.e., you can
      use destructuring, but you must put schema metadata on the top-level
      arguments, not the destructured variables.

      Bad:  (s/defn foo [{:keys [x :- s/Int]}])
      Good: (s/defn foo [{:keys [x]} :- {:x s/Int}])
    - Only a specific subset of rest-arg destructuring is supported:
      - & rest works as expected
      - & [a b] works, with schemas for individual elements parsed out of the binding,
        or an overall schema on the vector
      - & {} is not supported.
    - Unlike clojure.core/defn, a final attr-map on multi-arity functions
      is not supported."
  [& defn-args]
  (let [[name & more-defn-args] (macros/normalized-defn-args &env defn-args)
        {:keys [doc tag] :as standard-meta} (meta name)
        {:keys [outer-bindings schema-form fn-body arglists raw-arglists]} (macros/process-fn- &env name more-defn-args)]
    `(let ~outer-bindings
       (let [ret# (clojure.core/defn ~(with-meta name {})
                    ~(assoc (apply dissoc standard-meta (when (macros/primitive-sym? tag) [:tag]))
                            :doc (str
                                   (str "Inputs: " (if (= 1 (count raw-arglists))
                                                     (first raw-arglists)
                                                     (apply list raw-arglists)))
                                   (when-let [ret (when (= (second defn-args) :-) (nth defn-args 2))]
                                     (str "\n  Returns: " ret))
                                   (when doc (str  "\n\n  " doc)))
                            :raw-arglists (list 'quote raw-arglists)
                            :arglists (list 'quote arglists)
                            :schema schema-form)
                    ~@fn-body)]
         (utils/declare-class-schema! (utils/fn-schema-bearer ~name) ~schema-form)
         ret#)))))

#?(:clj
(defmacro defmethod
  "Like clojure.core/defmethod, except that schema-style typehints can be given on
   the argument symbols and after the dispatch-val (for the return value).

   See (doc s/defn) for details.

   Examples:

     (s/defmethod mymultifun :a-dispatch-value :- s/Num [x :- s/Int y :- s/Num] (* x y))

     ;; You can also use meta tags like ^:always-validate by placing them
     ;; before the multifunction name:

     (s/defmethod ^:always-validate mymultifun :a-dispatch-value [x y] (* x y))"
  [multifn dispatch-val & fn-tail]
  (let [methodfn `(fn ~(with-meta (gensym (str (name multifn) "__")) (meta multifn)) ~@fn-tail)]
    `(macros/if-cljs
       (cljs.core/-add-method
         ~(with-meta multifn {:tag 'cljs.core/MultiFn})
         ~dispatch-val
         ~methodfn)
       ~#?(:bb `(let [methodfn# ~methodfn]
                  (clojure.core/defmethod ~multifn ~dispatch-val [& args#] (apply methodfn# args#)))
           :default `(. ~(with-meta multifn {:tag 'clojure.lang.MultiFn})
                        addMethod
                        ~dispatch-val
                        ~methodfn))))))

(defonce
  ^{:doc
    "If the s/defprotocol instrumentation strategy is problematic
    for your platform, set atom to true and instrumentation will not
    be performed.

    Atom defaults to false."}
  ^:dynamic *elide-defprotocol-instrumentation* 
  (clojure.core/atom false))

(clojure.core/defn instrument-defprotocol?
  "If true, elide s/defprotocol instrumentation.

  Instrumentation is elided for any of the following cases:
  *   `@*elide-defprotocol-instrumentation*` is true during s/defprotocol macroexpansion
  *   `@*elide-defprotocol-instrumentation*` is true during s/defprotocol evaluation"
  []
  (not @*elide-defprotocol-instrumentation*))

#?(:clj
(defmacro defprotocol
  "Like clojure.core/defprotocol, except schema-style typehints can be provided for
  the argument symbols and after method names (for output schemas).

  ^:always-validate and ^:never-validate metadata can be specified for all
  methods on the protocol name. If specified on the method name, ignores
  the protocol name metadata and uses the method name metadata.

  Examples:

    (s/defprotocol MyProtocol
      \"Docstring\"
      :extend-via-metadata true
      (^:always-validate method1 :- s/Int
        [this a :- s/Bool]
        [this a :- s/Any, b :- s/Str]
        \"Method doc2\")
      (^:never-validate method2 :- s/Int
        [this]
        \"Method doc2\"))

  There is a performance penalty compared to `clojure.core/defprotocol`, even
  if instrumentation is disabled. It may be useful to set *elide-defprotocol-instrumentation*
  to `true` in production if you do not plan to check methods.
  
  Gotchas and limitations:
  - Implementation details are used to instrument protocol methods for schema
    checking. This is tested against a variety of platforms and versions,
    however if this is problematic for your environment, use
    *elide-defprotocol-instrumentation* to disable such instrumentation
    (either at compile-time or runtime depending on your needs).
    In ClojureScript, method var metadata will be overwritten unless disabled
    at compile-time. 
  - :schema metadata on protocol method vars is only supported in Clojure.
  - The Clojure compiler normally rewrites protocol method invocations to direct
    method calls if the target is type hinted as a class that directly extends the protocol's interface.
    This is disabled in s/defprotocol, as :inline metadata is added to protocol
    methods designed to defeat potential short-circuiting of schema checks. This also means
    compile-time errors for arity errors are suppressed (eg., `No single method` errors).
    Setting *elide-defprotocol-instrumentation* to true will restore the default behavior.
  - Methods cannot be instrumented in babashka due to technical limitations."
  [& name+opts+sigs]
  (let [{:keys [pname doc opts parsed-sigs]} (macros/process-defprotocol &env name+opts+sigs)
        sigs (map :sig parsed-sigs)
        defprotocol-form `(clojure.core/defprotocol
                            ~pname
                            ~@(when doc [doc])
                            ~@opts
                            ~@sigs)
        instrument? (instrument-defprotocol?)]
    `(do ~defprotocol-form
         ;; put everything that relies on protocol implementation details here so the user can
         ;; turn it off for whatever reason.
         ~@(when instrument?
             ;; in bb, protocol methods are multimethods. there's no way to be notified when
             ;; a multimethod is extended so we're stuck.
             #?(:bb nil
                :default (map (fn [{:keys [method-name instrument-method]}]
                                `(when (instrument-defprotocol?)
                                   ~instrument-method))
                              parsed-sigs)))
         ;; we always want s/fn-schema to work on protocol methods and have :schema
         ;; metadata on the var in Clojure.
         ~@(map (fn [{:keys [method-name schema-form]}]
                  `(let [fn-schema# ~schema-form]
                     ;; utils/declare-class-schema! works for subtly different reasons for each platform:
                     ;; :clj -- while CLJ-1796 means a method will change its identity after -reset-methods,
                     ;;         it does not change its class, as the same method builder is used each time.
                     ;;         fn-schema-bearer uses the class in :clj, so we're ok.
                     ;; :cljs -- method identity never changes, and fn-schema-bearer uses function identity in :cljs.
                     ;; :bb -- methods are multimethods which have defonce semantics are always class MultiFn. Object identity is used.
                     (utils/declare-class-schema! (macros/if-bb ~method-name (utils/fn-schema-bearer ~method-name)) fn-schema#)
                     ;; also add :schema metadata like s/defn
                     (macros/if-cljs
                       nil
                       (alter-meta! (var ~method-name) assoc :schema fn-schema#))))
                parsed-sigs)
         ~pname))))

#?(:clj
(defmacro letfn
  "s/letfn : s/fn :: clojure.core/letfn : clojure.core/fn
  
  Gotchas:
  - s/fn-schema will only work on direct references to the bindings
    inside the body. It will not work on intermediate calls between bindings."
  [fnspecs & body]
  (let [{:keys [outer-bindings
                fnspecs
                inner-bindings]}
        (reduce (fn [acc fnspec]
                  (let [[name more-fn-args] (macros/extract-arrow-schematized-element &env fnspec)
                        {:keys [outer-bindings schema-form fn-body]} (macros/process-fn- &env name more-fn-args)]
                    (-> acc
                        (update :outer-bindings into outer-bindings)
                        (update :fnspecs conj (cons name fn-body))
                        (update :inner-bindings conj name `(schematize-fn
                                                             ~name
                                                             ~schema-form)))))
                {:outer-bindings []
                 :fnspecs []
                 :inner-bindings []}
                fnspecs)]
    `(let ~outer-bindings
       (clojure.core/letfn
         ~fnspecs
         (let ~inner-bindings
           (do ~@body)))))))

#?(:clj
(defmacro def
  "Like def, but takes a schema on the var name (with the same format
   as the output schema of s/defn), requires an initial value, and
   asserts that the initial value matches the schema on the var name
   (regardless of the status of with-fn-validation).  Due to
   limitations of add-watch!, cannot enforce validation of subsequent
   rebindings of var.  Throws at compile-time for clj, and client-side
   load-time for cljs.

   Example:

   (s/def foo :- long \"a long\" 2)"
  [& def-args]
  (let [[name more-def-args] (macros/extract-arrow-schematized-element &env def-args)
        [doc-string? more-def-args] (if (= (count more-def-args) 2)
                                      (macros/maybe-split-first string? more-def-args)
                                      [nil more-def-args])
        init (first more-def-args)
        schema-form (macros/extract-schema-form name)]
    (macros/assert! (= 1 (count more-def-args)) "Illegal args passed to schema def: %s" def-args)
    `(let [output-schema# ~schema-form]
       (def ~name
         ~@(when doc-string? [doc-string?])
         (validate output-schema# ~init))))))

#?(:clj
(set! *warn-on-reflection* false))

(clojure.core/defn set-max-value-length!
  "Sets the maximum length of value to be output before it is contracted to a prettier name."
  [max-length]
  (reset! utils/max-value-length max-length))
