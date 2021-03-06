(ns cayenne.rdf
  (:import [com.hp.hpl.jena.rdf.model ModelFactory SimpleSelector])
  (:require [clojure.java.io :as io]))

(defn get-property [ns model val] (.getProperty model ns val))
(defn get-resource [model uri] (.getResource model uri))
(defn get-type [ns model val] (.getResource model (str ns val)))

(defn document->model [f]
  (with-open [rdr (io/reader f)]
    (-> (ModelFactory/createDefaultModel)
        (.read rdr nil))))

(defn select-seq [iter]
  (when (.hasNext iter)
    (cons 
     (let [stmt (.nextStatement iter)]
       (vector (.getSubject stmt) (.getPredicate stmt) (.getObject stmt)))
     (lazy-seq (select-seq iter)))))

(defn select [model & {:keys [subject predicate object]
                       :or {subject nil predicate nil object nil}}]
  (-> (.listStatements model subject predicate object) (select-seq)))

(defn subject [stmt] (first stmt))
(defn predicate [stmt] (second stmt))
(defn object [stmt] (nth stmt 2))

(defn subjects [s] (map subject s))
(defn predicates [s] (map predicate s))
(defn objects [s] (map object s))

(defn ->uri [resource] (.getURI resource))

;; Ontology namespaces

(def skos (partial get-property "http://www.w3.org/2004/02/skos/core#"))
(def skos-xl (partial get-property "http://www.w3.org/2008/05/skos-xl#"))
(def rdf (partial get-property "http://www.w3.org/1999/02/22-rdf-syntax-ns#"))
(def rdfs (partial get-property "http://www.w3.org/2000/01/rdf-schema#"))
(def dct (partial get-property "http://www.w3.org/2000/01/rdf-schema#"))
(def vcard (partial get-property "http://www.w3.org/2006/vcard/ns#"))
(def foaf (partial get-property "http://xmlns.com/foaf/0.1/"))

(def skos-type (partial get-type "http://www.w3.org/2004/02/skos/core#"))
