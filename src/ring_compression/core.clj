(ns ring-compression.core
  (:require [ring.core.protocols :as protos]
            [clojure.string :as strings]
            [clojure.set :as sets]
            [clojure.java.io :as io])
  (:import (java.io OutputStream File FileOutputStream)
           (java.util.zip GZIPOutputStream DeflaterOutputStream)
           (clojure.lang Reflector)
           (org.apache.commons.io.output TeeOutputStream)))

(defn parse-accepted-encoding [encoding]
  (let [re #"^(gzip|compress|deflate|br|identity|\*)(?:;q=([\d.]+))?$"]
    (when-some [[_ algorithm priority] (re-find re encoding)]
      {:algorithm algorithm :priority (Double/parseDouble (or priority "1"))})))

(defn get-encoding-maps [accept-encoding-header]
  (->> (strings/split accept-encoding-header #"\s*,\s*")
       (keep parse-accepted-encoding)
       (vec)))

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

(defn get-content-encoding [response]
  (or (get-in response [:headers "Content-Encoding"])
      (get-in response [:headers "content-encoding"])))

(defn has-class? [class-name]
  (try (Class/forName class-name) true (catch Exception e false)))

(defn construct-dynamic [class-name & args]
  (Reflector/invokeConstructor (Class/forName class-name) (into-array Object args)))

(def default-gzip-class "java.util.zip.GZIPOutputStream")
(def default-deflate-class "java.util.zip.DeflaterOutputStream")
(def default-brotli-class "org.brotli.wrapper.enc.BrotliOutputStream")
(def alternative-brotli-class "com.nixxcode.jvmbrotli.enc.BrotliOutputStream")
(def has-default-brotli (has-class? default-brotli-class))
(def has-alternative-brotli (has-class? alternative-brotli-class))

(def prefer-brotli
  (cond-> []
    has-default-brotli
    (conj {:algorithm "br" :priority 1.0})
    (and (not has-default-brotli)
         has-alternative-brotli
         (eval `(com.nixxcode.jvmbrotli.common.BrotliLoader/isBrotliAvailable)))
    (conj {:algorithm "br" :priority 1.0})
    (has-class? default-gzip-class)
    (conj {:algorithm "gzip" :priority 0.9})
    (has-class? default-deflate-class)
    (conj {:algorithm "deflate" :priority 0.8})))

(def prefer-gzip
  (cond-> []
    (has-class? default-gzip-class)
    (conj {:algorithm "gzip" :priority 1.0})
    has-default-brotli
    (conj {:algorithm "br" :priority 0.9})
    (and (not has-default-brotli)
         has-alternative-brotli
         (eval `(com.nixxcode.jvmbrotli.common.BrotliLoader/isBrotliAvailable)))
    (conj {:algorithm "br" :priority 0.9})
    (has-class? default-deflate-class)
    (conj {:algorithm "deflate" :priority 0.8})))

(def default-preferences-by-content-type
  {"text/javascript"          prefer-brotli
   "text/css"                 prefer-brotli
   "text/html"                prefer-brotli
   "application/javascript"   prefer-brotli
   "text/*"                   prefer-gzip
   "image/svg+xml"            prefer-gzip
   "application/json"         prefer-gzip
   "application/xml"          prefer-gzip
   "application/edn"          prefer-gzip
   "application/transit+json" prefer-gzip
   "*"                        []})

(def default-compressors
  {"gzip"     (fn [^OutputStream stream]
                (GZIPOutputStream. stream))
   "deflate"  (fn [^OutputStream stream]
                (DeflaterOutputStream. stream))
   "br"       (fn [^OutputStream stream]
                (cond
                  has-default-brotli
                  (construct-dynamic default-brotli-class stream)
                  has-alternative-brotli
                  (construct-dynamic alternative-brotli-class stream)
                  :otherwise
                  (throw (ex-info "Brotli not properly configured." {}))))
   "identity" identity})

(defn finalize-preferences [preferences]
  (reduce (fn [agg {:keys [algorithm priority]}]
            (if (zero? priority)
              (dissoc agg algorithm)
              (update agg algorithm #(or % {:algorithm algorithm :priority priority}))))
          {"identity" {:algorithm "identity" :priority 0.001}}
          preferences))

(defn bubble-top-algos [preferences]
  (reduce
    (fn [[max-score algorithms :as agg] {:keys [algorithm priority]}]
      (cond
        (< max-score priority)
        [priority #{algorithm}]
        (== max-score priority)
        [max-score (conj algorithms algorithm)]
        :otherwise
        agg))
    [Double/MIN_VALUE #{}]
    preferences))

(defn negotiate [server-preferences client-preferences]
  (let [compiled-server  (finalize-preferences server-preferences)
        compiled-client  (finalize-preferences client-preferences)
        remove-keys      (sets/difference (set (keys compiled-client)) (set (keys compiled-server)))
        compiled-client' (apply dissoc compiled-client (disj remove-keys "*"))
        [_ algorithms] (bubble-top-algos (vec (vals compiled-client')))]
    (cond
      (or (contains? algorithms "*") (< 1 (count algorithms)))
      (->> (if (contains? algorithms "*") (keys compiled-server) algorithms)
           (mapv compiled-server)
           (reduce (fn [[max-score winner] {:keys [priority algorithm]}]
                     (if (< max-score priority)
                       [priority algorithm]
                       [max-score winner]))
                   [Double/MIN_VALUE nil])
           (second))
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
  "This middleware performs compression on demand. There are sensible defaults
   but you can further express preferences for which compression algorithms to
   use against various content types of the response. Expressing no compression
   algorithms for a content type guarantees those responses won't be compressed."
  ([handler]
   (wrap-compression handler {}))
  ([handler {:keys [compressors server-preferences]
             :or   {compressors        default-compressors
                    server-preferences default-preferences-by-content-type}}]
   (letfn [(make-response [request response]
             ; make sure that compression done inside of this middleware
             ; does not get double compressed / renegotiated
             (if (some? (get-content-encoding response))
               response
               (let [client-accept-encoding (get-accepted-encoding request)
                     vary                   (get-vary response)
                     response-content-type  (get-content-type response)
                     client-preferences     (get-encoding-maps client-accept-encoding)
                     server-preferences     (determine-server-preferences server-preferences response-content-type)
                     algorithm              (negotiate server-preferences client-preferences)]
                 (cond
                   (= "identity" algorithm)
                   response
                   (nil? algorithm)
                   {:status  406
                    :headers {"Content-Type" "text/plain"}
                    :body    "Server cannot satisfy the accept-encoding header presented in the request."}
                   (not= "identity" algorithm)
                   (-> response
                       (assoc-in [:headers "Content-Encoding"] algorithm)
                       (assoc-in [:headers "Vary"] (if (strings/blank? vary) "Content-Encoding" (str vary ", Content-Encoding")))
                       (update :headers dissoc "Content-Length" "content-length" "content-encoding" "vary")
                       (assoc :body (reify protos/StreamableResponseBody
                                      (write-body-to-stream [body res output-stream]
                                        (with-open [out ((get compressors algorithm) output-stream)]
                                          (protos/write-body-to-stream (:body response) res out))))))))))]
     (fn compression-handler
       ([request]
        (make-response request (handler request)))
       ([request respond raise]
        (handler request (fn [response] (respond (make-response request response))) raise))))))



(defn wrap-response-caching
  "This middleware performs response caching. The body of a cached response is written
   to and served from disk. The other data on a response is held in memory in an atom.

   This middleware is intended primarily for use in the following way:

   (-> (wrap-resources {:root \"public\"}
       (wrap-compression)
       (wrap-response-caching))

   "
  ([handler]
   (wrap-response-caching handler {}))
  ([handler {:keys [cache-key-fn cacheable-response?]}]
   (letfn [(can-cache? [response]
             (if (ifn? cacheable-response?)
               (cacheable-response? response)
               (< 200 (:status response) 299)))
           (compute-cache-key [request]
             (if (ifn? cache-key-fn)
               (cache-key-fn request)
               (let [client-accept-encoding (get-accepted-encoding request)
                     client-accepts         (set (map :algorithm (get-encoding-maps client-accept-encoding)))]
                 [(:request-method request) (:uri request) client-accepts])))
           (clean-response-for-safe-reuse [response]
             (-> response
                 (update :headers (fnil dissoc {}) "Set-Cookie" "set-cookie")
                 (select-keys [:body :status :headers])))
           (caching-response [cache cache-key response]
             (if (can-cache? response)
               (update response :body
                       (fn [original-body]
                         (reify protos/StreamableResponseBody
                           (write-body-to-stream [_ res output-stream]
                             (let [cache-file
                                   (doto (File/createTempFile "" ".res")
                                     (.deleteOnExit))]
                               (try
                                 (with-open
                                   [out (TeeOutputStream.
                                          ^OutputStream output-stream
                                          ^OutputStream (FileOutputStream. cache-file))]
                                   (protos/write-body-to-stream original-body res out))
                                 (catch Exception e
                                   (io/delete-file cache-file true)
                                   (throw e)))
                               (swap! cache update cache-key
                                      (fn [cached-response]
                                        (if (some? cached-response)
                                          (do (io/delete-file cache-file true) cached-response)
                                          (-> response
                                              (clean-response-for-safe-reuse)
                                              (assoc :body cache-file))))))))))
               response))]
     (let [cache (atom {})]
       (fn
         ([request]
          (let [cache-key     (compute-cache-key request)
                current-cache (deref cache)]
            (if (contains? current-cache cache-key)
              (get current-cache cache-key)
              (caching-response cache cache-key (handler request)))))
         ([request respond raise]
          (let [cache-key     (compute-cache-key request)
                current-cache (deref cache)]
            (if (contains? current-cache cache-key)
              (respond (get current-cache cache-key))
              (handler request (fn [response] (respond (caching-response cache cache-key response))) raise)))))))))
