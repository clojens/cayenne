(ns cayenne.api.route
  (:import [java.net URL])
  (:require [cayenne.ids :as ids]
            [cayenne.ids.doi :as doi-id]
            [cayenne.conf :as conf]
            [cayenne.data.deposit :as d]
            [cayenne.data.core :as c]
            [cayenne.data.doi :as doi]
            [cayenne.api.types :as t]
            [cayenne.api.query :as q]
            [clojure.data.json :as json]
            [liberator.core :refer [defresource resource]]
            [liberator.dev :refer [wrap-trace]]
            [metrics.ring.expose :refer [expose-metrics-as-json]]
            [metrics.ring.instrument :refer [instrument]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace-web]]
            [compojure.core :refer [defroutes ANY]]
            [compojure.handler :as handler]))

(extend java.util.Date json/JSONWriter {:-write #(json/write (.toString %1) %2)})
(extend org.bson.types.ObjectId json/JSONWriter {:-write #(json/write (.toString %1) %2)})

(defn ->1
  "Helper that creates a function that calls f while itself taking one
   argument which is ignored."
  [f]
  (fn [_] (f)))

(defn abs-url
  "Create an absolute url to a resource relative to the given request url. The
   request url acts as the base of a path created by concatentating paths."
  [request & paths]
  (URL. (format "%s://%s:%s%s/%s"
                (name (:scheme request))
                (:server-name request)
                (:server-port request)
                (:uri request)
                (clojure.string/join "/" paths))))

(defn content-type-matches
  "True if the content type contained in ctx matches any of those listed
   in cts."
  [ctx cts]
  (let [ct (get-in ctx [:request :headers "content-type"])]
    (some #{ct} cts)))

(defresource deposits-resource [data]
  :allowed-methods [:post]
  :known-content-type? #(content-type-matches % t/depositable)
  :available-media-types t/html-or-json
  :post-redirect? #(hash-map :location (abs-url (:request %) (:id %)))
  :post! #(hash-map :id (d/create! (get-in % [:request :headers "content-type"]) data)))

(defresource deposit-resource [id]
  :allowed-methods [:get]
  :available-media-types t/html-or-json
  :exists? (->1 #(when-let [deposit (d/fetch id)] {:deposit deposit}))
  :handle-ok :deposit)

(defresource deposit-data-resource [id]
  :allowed-methods [:get]
  :media-type-available? (constantly true)
  :exists? (->1 #(when-let [deposit (d/fetch id)] {:deposit deposit}))
  :handle-ok (->1 #(d/fetch-data id)))

(defresource prefix-resource [prefix])

(defresource subjects-resource)

(defresource subject-resource [subject-id])

(defresource dois-resource
  :allowed-methods [:get]
  :media-type-available? t/html-or-json
  :handle-ok #(json/write-str (doi/fetch-dois (q/->query-context %))))

(defresource doi-resource [doi]
  :allowed-methods [:get]
  :media-type-available? t/html-or-json
  :handle-ok (->1 #(json/write-str (doi/fetch-doi (doi-id/to-long-doi-uri doi)))))

(defresource random-dois-resource [count]
  :allowed-methods [:get]
  :media-type-available? t/html-or-json
  :handle-ok (->1 #(json/write-str (doi/fetch-random-dois count))))

(defresource cores-resource
  :allowed-methods [:get]
  :media-type-available? t/html-or-json
  :handle-ok (->1 #(json/write-str (c/fetch-all))))

(defresource core-resource [core-name]
  :allowed-methods [:get]
  :available-media-types t/html-or-json
  :exists? (->1 #(c/exists? core-name))
  :handle-ok (->1 #(json/write-str (c/fetch core-name))))

(defroutes api-routes
  (ANY "/items" []
       dois-resource)
  (ANY "/items/random/:count" [count]
       (random-dois-resource count))
  (ANY "/items/:doi" [doi]
       (doi-resource doi))
  (ANY "/cores" []
       cores-resource)
  (ANY "/cores/:name" [name]
       (core-resource name))
  (ANY "/deposits" {body :body}
       (deposits-resource body))
  (ANY "/deposits/:id" [id]
       (deposit-resource id))
  (ANY "/deposits/:id/data" [id]
       (deposit-data-resource id)))

(defn wrap-cors
  [h]
  (fn [request]
    (-> (h request)
        (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
        (assoc-in [:headers "Access-Control-Allow-Headers"]
                  "X-Requested-With"))))

(def api
  (-> api-routes
      (handler/api)
      (wrap-cors)
      (expose-metrics-as-json)
      (instrument)
      (wrap-trace :ui)
      (wrap-stacktrace-web)))

(conf/with-core :default 
  (conf/set-param! [:service :api :var] #'api))
