(ns cayenne.action
  (:import [java.net URLEncoder])
  (:use [cayenne.conf]
        [cayenne.sources.wok]
        [cayenne.tasks.dump]
        [cayenne.tasks.citation]
        [clojure.tools.trace]
        [cayenne.formats.unixref :only [unixref-record-parser unixref-citation-parser]]
        [cayenne.formats.datacite :only [datacite-record-parser]])
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [cayenne.oai :as oai]
            [cayenne.job :as job]
            [cayenne.html :as html]
            [cayenne.tasks.category :as cat]
            [cayenne.tasks.doaj :as doaj]
            [cayenne.tasks.funder :as funder]
            [cayenne.tasks.neo4j :as neo4j]
            [cayenne.tasks.mongo :as mongo]
            [cayenne.item-tree :as itree]
            [cayenne.tasks.solr :as solr]
            [cayenne.conf :as conf]
            [cayenne.ids.doi :as doi]))

(defn scrape-journal-short-names-from-wok []
  (html/scrape-urls 
   journal-pages 
   :scraper journal-names-scraper 
   :task (record-writer "out.txt")))

(defn openurl-file [doi]
  (let [extracted-doi (doi/extract-long-doi doi)
        url (str (get-param [:upstream :openurl-url]) 
                 (URLEncoder/encode extracted-doi))]
    (remote-file url)))

(def dump-plain-docs
  (record-json-writer "out.txt"))

(def dump-annotated-docs
  (comp
   (record-json-writer "out.txt")
   #(apply funder/apply-to %)
   #(apply doaj/apply-to %)
   #(apply cat/apply-to %)))

(def dump-solr-docs
  (comp
   (record-json-writer "out.txt")
   solr/as-solr-document
   #(assoc % :source "CrossRef")
   #(apply itree/centre-on %)
   #(apply funder/apply-to %)
   #(apply doaj/apply-to %)
   #(apply cat/apply-to %)))

(def dump-plain-solr-docs
  (comp
   (record-json-writer "out.txt")
   solr/as-solr-document
   #(assoc % :source "CrossRef")
   #(apply itree/centre-on %)))

(def print-solr-docs
  (comp
   #(cayenne.conf/log %)
   solr/as-solr-document
   #(assoc % :source "CrossRef")
   #(apply itree/centre-on %)
   #(apply funder/apply-to %)
   #(apply doaj/apply-to %)
   #(apply cat/apply-to %)))

(def dump-triples
  (comp
   (record-json-writer "out.txt")
   neo4j/as-triples
   second))

(def index-solr-docs
  (comp 
   solr/insert-item
   #(assoc % :source "CrossRef")
   #(apply itree/centre-on %)
   #(apply funder/apply-to %)
   #(apply doaj/apply-to %)
   #(apply cat/apply-to %)))

(def graph-triples
  (comp
   neo4j/insert-item
   second))

(def store-item
  (comp
   (partial mongo/insert-item "items")
   #(assoc % :source "CrossRef")
   #(apply itree/centre-on %)
   #(apply funder/apply-to %)
   #(apply doaj/apply-to %)
   #(apply cat/apply-to %)))

(defn parse-unixref-records [file-or-dir using]
  (oai/process file-or-dir
               :async true
               :split "record"
               :parser unixref-record-parser 
               :task using))

(defn parse-datacite-records [file-or-dir using]
  (oai/process file-or-dir
               :async true
               :split "resource"
               :parser datacite-record-parser
               :task using))

(defn parse-openurl [doi using]
  (oai/process (openurl-file doi)
               :async true
               :kind ".tmp"
               :split "doi_record"
               :parser unixref-record-parser
               :task (comp 
                      using
                      #(vector (doi/to-long-doi-uri doi) (second %)))))

(defn parse-openurl-list [list-file using]
  (with-open [rdr (io/reader (io/file list-file))]
    (doseq [doi (line-seq rdr)]
      (oai/process (openurl-file doi)
                   :async true
                   :kind ".tmp"
                   :split "doi_record"
                   :parser unixref-record-parser
                   :task (comp
                          using
                          #(vector (doi/to-long-doi-uri doi) (second %)))))))

(defn get-unixref-records [service from until using]
  (oai/run-range service
                 :from from 
                 :until until
                 :split "record"
                 :parser unixref-record-parser
                 :task using))

(defn reindex-fundref [funder-list-loc]
    (let [funder-info (-> funder-list-loc
                        (slurp)
                        (json/read-str))]
    (doseq [doi (get funder-info "items")]
      (parse-openurl doi index-solr-docs))
    (cayenne.tasks.solr/flush-insert-list)))

(defn rerun-oai-service [service from until action]
  (let [existing-dir (clojure.java.io/file (:dir service)
                                           (str from "-" until))]
    (doseq [f (.listFiles existing-dir)]
      (.delete f))
    (get-unixref-records service from until action)))

(defn rerun-cr-failed 
  "Retry a failed CrossRef OAI-PMH download represented by a fail log line."
  [log-line action]
  (if (re-find #":file " log-line)

    (let [path (second (re-find #":file ([\w/-]+)" log-line))
          path-split (reverse (string/split path #"/"))
          from-until (string/split (second path-split) #"-")
          from (string/join "-" (take 3 from-until))
          until (string/join "-" (take 3 (drop 3 from-until)))
          service (conf/get-param [:oai (keyword (nth path-split 2))])]
      (rerun-oai-service service from until action))

    (let [from (second (re-find #":from ([0-9-]+)" log-line))
          until (second (re-find #":until ([0-9-]+)" log-line))
          service (condp = (second (re-find #":set-spec (J|S|B)" log-line))
                    "J" (conf/get-param [:oai :crossref-journals])
                    "S" (conf/get-param [:oai :crossref-serials])
                    "B" (conf/get-param [:oai :crossref-books]))]
      (rerun-oai-service service from until action))))

(defn rerun-all-cr-failed 
  "Retry CrossRef OAI-PMH download of each failed download in a log file."
  [log-file action]
  (with-open [rdr (clojure.java.io/reader log-file)]
    (doseq [log-line (line-seq rdr)]
      (when (re-find #":state :fail" log-line)
        (rerun-cr-failed log-line action)))))

(defn find-doi 
  "Look up a DOI via CrossRef Metadata Search."
  [doi]
  (let [search-url (-> (get-param [:upstream :crmds-dois]) 
                       (str doi) 
                       (java.net.URL.))
        response (-> search-url (slurp) (json/read-str))]
    [doi (not (empty? response))]))

(defn find-dois 
  "Look up a list of DOIs via CrossRef Metadata Search. Result is a map mapping
   DOI to CRMDS response."
  [doi-list-loc]
  (into {} (map find-doi (-> doi-list-loc (line-seq) (distinct)))))

(defn check-url-citations [file-or-dir]
  (oai/process
   file-or-dir
   :parser unixref-record-parser
   :task (url-citation-checker "check.log.txt")))

(defn find-citations-like [file-or-dir patt]
  (oai/process
   file-or-dir
   :parser unixref-citation-parser 
   :task (matching-citation-finder "match.log.txt" patt)))

(defn find-standards-citations [file-or-dir]
  (let [patt #"^(ASTM [A-G]|ISO |IEC |ISO/IEC |EN |EN ISO |BS |BS ISO |BS EN ISO |IEEE [A-Z]?)[0-9]+((\.|-)[0-9]+)? ((\.|-)[0-9]+)?(:[0-9]{4})?"]
    (find-citations-like file-or-dir patt)))

(defn find-standards-citations-loose [file-or-dir]
  (let [patt #"(ASTM [A-G]|ISO |IEC |ISO/IEC |EN |EN ISO |BS |BS ISO |BS EN ISO |IEEE [A-Z]?)[0-9]+((\.|-)[0-9]+)? ((\.|-)[0-9]+)?(:[0-9]{4})?"]
    (find-citations-like file-or-dir patt)))

