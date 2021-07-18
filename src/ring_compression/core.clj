(ns ring-compression.core
  (:require [ring.core.protocols :as protos]
            [clojure.string :as strings]
            [clojure.set :as sets])
  (:import (java.io OutputStream)
           (java.util.zip GZIPOutputStream DeflaterOutputStream)
           (clojure.lang Reflector)))

(defn parse-accepted-encoding [encoding]
  (let [re #"(gzip|compress|deflate|br|identity|\*)(?:;q=([\d.]+))?"]
    (when-some [[_ algorithm priority] (re-find re encoding)]
      {:algorithm algorithm :priority (Double/parseDouble (or priority "1"))})))

(defn get-encoding-maps [accept-encoding-header]
  (->> (strings/split accept-encoding-header #"\s*,\s*")
       (remove strings/blank?)
       (map parse-accepted-encoding)
       (sort-by :priority #(compare %2 %1))))

(defn get-accepted-encoding [request]
  (or (get-in request [:headers "Accept-Encoding"])
      (get-in request [:headers "accept-encoding"])
      "*"))

(defn get-content-type [response]
  (or (get-in response [:headers "Content-Type"])
      (get-in response [:headers "content-type"])
      "application/octet-stream"))

(defn get-vary [response]
  (or (get-in response [:headers "Vary"])
      (get-in response [:headers "vary"])))

(defn has-class? [class-name]
  (try (Class/forName class-name) true (catch Exception e false)))

(defn construct-dynamic [class-name & args]
  (Reflector/invokeConstructor (Class/forName class-name) (into-array Object args)))

(def default-gzip-class "java.util.zip.GZIPOutputStream")
(def default-deflate-class "java.util.zip.DeflaterOutputStream")
(def default-brotli-class "org.brotli.wrapper.enc.BrotliOutputStream")

(def prefer-brotli
  (cond-> []
    (has-class? default-brotli-class)
    (conj {:algorithm "br" :priority 1.0})
    (has-class? default-gzip-class)
    (conj {:algorithm "gzip" :priority 0.9})
    (has-class? default-deflate-class)
    (conj {:algorithm "deflate" :priority 0.8})))

(def prefer-gzip
  (cond-> []
    (has-class? default-gzip-class)
    (conj {:algorithm "gzip" :priority 1.0})
    (has-class? default-deflate-class)
    (conj {:algorithm "deflate" :priority 0.9})))

(def default-preferences-by-content-type
  {"text/*"                 prefer-brotli
   "application/json"       prefer-brotli
   "application/javascript" prefer-brotli
   "application/xml"        prefer-brotli
   "image/svg+xml"          prefer-brotli
   "application/*"          []
   "image/*"                []
   "video/*"                []
   "*"                      prefer-gzip})

(def default-compressors
  {"gzip"     (fn [^OutputStream stream]
                (GZIPOutputStream. stream))
   "deflate"  (fn [^OutputStream stream]
                (DeflaterOutputStream. stream))
   "br"       (fn [^OutputStream stream]
                (construct-dynamic default-brotli-class stream))
   "identity" identity})

(defn finalize-preferences [preferences]
  (reduce (fn [agg {:keys [algorithm priority]}]
            (if (zero? priority)
              (dissoc agg algorithm)
              (if (contains? agg algorithm)
                agg
                (assoc agg algorithm {:algorithm algorithm :priority priority}))))
          {}
          (into [{:algorithm "identity" :priority 0.001}] preferences)))

(defn prioritized-preferences [preferences]
  (->> (vals preferences)
       (reduce (fn [agg {:keys [algorithm priority]}]
                 (update agg priority (fnil conj #{}) algorithm)) {})
       (into (sorted-map-by #(compare %2 %1)))))

(defn negotiate [server-preferences client-preferences]
  (let [compiled-server  (finalize-preferences server-preferences)
        compiled-client  (finalize-preferences client-preferences)
        shared-keys      (sets/intersection (set (keys compiled-server)) (set (keys compiled-client)))
        compiled-client' (select-keys compiled-client (conj shared-keys "*"))
        [_ algorithms] (first (prioritized-preferences compiled-client'))]
    (cond
      (contains? algorithms "*")
      (second
        (reduce (fn [[max-score winner] {:keys [priority algorithm]}]
                  (if (< max-score priority)
                    [priority algorithm]
                    [max-score winner]))
                [Double/MIN_VALUE nil]
                (vals compiled-server)))
      (< 1 (count algorithms))
      (second
        (reduce (fn [[max-score winner] {:keys [priority algorithm]}]
                  (if (< max-score priority)
                    [priority algorithm]
                    [max-score winner]))
                [Double/MIN_VALUE nil]
                (map compiled-server algorithms)))
      :otherwise
      (first algorithms))))

(defn determine-server-preferences [preferences-by-content-type response-content-type]
  (loop [[path & remainder]
         [(strings/replace response-content-type #"\s*;.*" "")
          (strings/replace response-content-type #"/.*" "/*")
          "*"]]
    (if (contains? preferences-by-content-type path)
      (get preferences-by-content-type path)
      (recur remainder))))

(defn wrap-compression
  ([handler]
   (wrap-compression handler {}))
  ([handler {:keys [compressors server-preferences]
             :or   {compressors        default-compressors
                    server-preferences default-preferences-by-content-type}}]
   (letfn [(make-response [request response]
             (let [client-accept-encoding (get-accepted-encoding request)
                   vary                   (get-vary response)
                   response-content-type  (get-content-type response)
                   client-preferences     (get-encoding-maps client-accept-encoding)
                   server-preferences     (determine-server-preferences server-preferences response-content-type)
                   algorithm              (negotiate server-preferences client-preferences)]
               (cond
                 (nil? algorithm)
                 {:status  406
                  :headers {"Content-Type" "text/plain"}
                  :body    "Server cannot satisfy the accept-encoding priorities present in the request."}
                 (= "identity" algorithm)
                 response
                 (not= "identity" algorithm)
                 (-> response
                     (assoc-in [:headers "Content-Encoding"] algorithm)
                     (assoc-in [:headers "Vary"] (if (strings/blank? vary) "Content-Encoding" (str vary ", Content-Encoding")))
                     (update :headers dissoc "Content-Length" "content-length" "content-encoding" "vary")
                     (assoc :body (reify protos/StreamableResponseBody
                                    (write-body-to-stream [body response output-stream]
                                      (with-open [out ((get compressors algorithm) output-stream)]
                                        (protos/write-body-to-stream (:body response) response out)))))))))]
     (fn compression-handler
       ([request] (make-response request (handler request)))
       ([request respond raise]
        (handler request (fn [response] (respond (make-response request response))) raise))))))

