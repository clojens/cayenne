(ns cayenne.api.filter
  (:require [clojure.string :as string]))

; build solr filters

(defn field-is [field-name match]
  (str field-name ":" match))

(defn field-gt [field-name val]
  (str field-name ":[" (+ 1 val) " TO *]"))

(defn field-gte [field-name val]
  (str field-name ":[" val " TO *]"))

(defn field-lt [field-name val]
  (str field-name ":[* TO " (- val 1) "]"))

(defn field-lte [field-name val]
  (str field-name ":[* TO " val "]"))

(defn q-or [& more]
  (str "(" (string/join " " (interpose "OR" more)) ")"))

(defn q-and [& more]
  (str "(" (string/join " " (interpose "AND" more)) ")"))

(defn field-lt-or-gt [field-name val end-point]
  (cond 
   (= end-point :from)
   (field-gt field-name val)
   (= end-point :until)
   (field-lt field-name val)))

(defn field-lte-or-gte [field-name val end-point]
  (cond
   (= end-point :from)
   (field-gte field-name val)
   (= end-point :until)
   (field-lte field-name val)))

(defn split-date [date-str]
  (let [date-parts (string/split date-str #"-")]
    {:year (Integer/parseInt (first date-parts))
     :month (Integer/parseInt (second date-parts))
     :day (Integer/parseInt (nth date-parts 2))}))

(defn stamp-date [date-stamp-field direction]
  (fn [val]
    ()))

(defn particle-date [year-field month-field day-field end-point]
  (fn [val]
    (let [d (split-date val)]
      (cond (:day d)
            (q-or
             (field-lt-or-gt year-field (:year d) end-point)
             (q-and (field-is year-field (:year d))
                    (field-lt-or-gt month-field (:month d) end-point))
             (q-and (field-is year-field (:year d))
                    (field-is month-field (:month d))
                    (field-lte-or-gte day-field (:day d) end-point)))
            (:month d)
            (q-or
             (field-lt-or-gt year-field (:year d) end-point)
             (q-and (field-is year-field (:year d))
                    (field-lte-or-gte month-field (:month d) end-point)))
            (:year d)
             (field-lte-or-gte year-field (:year d) end-point)))))

(defn existence [field]
  (fn [val]
    (cond (#{"t" "true" "1"} (.toLowerCase val))
          (str field ":[* TO *]")
          (#{"f" "false" "0"} (.toLowerCase val))
          (str field ":-[* TO *]"))))

(defn equality [field]
  (fn [val]
    (str field ":\"" val "\"")))
