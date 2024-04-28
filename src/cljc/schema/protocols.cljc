(ns schema.protocols)

(defprotocol SchemaSyntax
  (original-syntax [this] "Returns the original syntax for a schema. Returns this if none."))
