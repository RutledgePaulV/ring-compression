(ns ring-compression.core-test
  (:require [clojure.test :refer :all]
            [ring-compression.core :refer :all]
            [ring.core.protocols :as protos])
  (:import (java.io ByteArrayOutputStream ByteArrayInputStream)
           (java.util.zip GZIPInputStream InflaterInputStream)
           (com.nixxcode.jvmbrotli.dec BrotliInputStream)))

(deftest parsing-headers-test
  (testing "no header defaults to server preferences"
    (is (= "*" (get-accepted-encoding {}))))
  (testing "uppercase header"
    (is (= "identity;q=1" (get-accepted-encoding {:headers {"Accept-Encoding" "identity;q=1"}}))))
  (testing "lowercase header"
    (is (= "identity;q=1" (get-accepted-encoding {:headers {"accept-encoding" "identity;q=1"}}))))
  (testing "default priority to 1"
    (is (= [{:algorithm "identity" :priority 1.0}]
           (get-encoding-maps (get-accepted-encoding {:headers {"accept-encoding" "identity"}})))))
  (testing "multiple encodings"
    (is (= [{:algorithm "deflate", :priority 1.0} {:algorithm "gzip", :priority 0.8}]
           (get-encoding-maps (get-accepted-encoding {:headers {"accept-encoding" "deflate;q=1,gzip;q=0.8"}}))))))

(deftest negotiate-test
  (testing "unspecified preference by client indicates identity"
    (let [server [{:algorithm "gzip", :priority 0.9}
                  {:algorithm "deflate", :priority 0.8}]
          client []]
      (is (= "identity" (negotiate server client)))))
  (testing "client wildcard defers to server preference"
    (let [server [{:algorithm "gzip", :priority 0.9}
                  {:algorithm "deflate", :priority 0.8}]
          client [{:algorithm "*" :priority 1.0}]]
      (is (= "gzip" (negotiate server client)))))
  (testing "client preference is tie broken by server preference"
    (let [server [{:algorithm "gzip", :priority 0.9}
                  {:algorithm "deflate", :priority 0.8}]
          client [{:algorithm "deflate" :priority 1.0}
                  {:algorithm "gzip" :priority 1.0}]]
      (is (= "gzip" (negotiate server client)))))
  (testing "client preference is considered over server preference"
    (let [server [{:algorithm "gzip", :priority 1.0}
                  {:algorithm "br", :priority 0.9}
                  {:algorithm "deflate", :priority 0.8}]
          client [{:algorithm "deflate" :priority 0.9}
                  {:algorithm "gzip" :priority 0.8}]]
      (is (= "deflate" (negotiate server client)))))
  (testing "client can opt out of identity"
    (let [server []
          client [{:algorithm "identity" :priority 0}]]
      (is (nil? (negotiate server client))))))

(defn mock-handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "content content content content"})

(deftest wrap-compression-test

  (testing "no accept encoding"
    (let [handler  (wrap-compression mock-handler)
          request  {}
          response (handler request)]
      (is (= "gzip" (get-in response [:headers "Content-Encoding"])))
      (is (= "content content content content"
             (with-open [out (ByteArrayOutputStream.)]
               (protos/write-body-to-stream (:body response) response out)
               (slurp (GZIPInputStream. (ByteArrayInputStream. (.toByteArray out)))))))))

  (testing "brotli compression"
    (let [handler  (wrap-compression mock-handler)
          request  {:headers {"Accept-Encoding" "br"}}
          response (handler request)]
      (is (= "br" (get-in response [:headers "Content-Encoding"])))
      (is (= "content content content content"
             (with-open [out (ByteArrayOutputStream.)]
               (protos/write-body-to-stream (:body response) response out)
               (slurp (BrotliInputStream. (ByteArrayInputStream. (.toByteArray out)))))))))

  (testing "gzip compression"
    (let [handler  (wrap-compression mock-handler)
          request  {:headers {"Accept-Encoding" "gzip"}}
          response (handler request)]
      (is (= "gzip" (get-in response [:headers "Content-Encoding"])))
      (is
        (= "content content content content"
           (with-open [out (ByteArrayOutputStream.)]
             (protos/write-body-to-stream (:body response) response out)
             (slurp (GZIPInputStream. (ByteArrayInputStream. (.toByteArray out)))))))))

  (testing "deflate compression"
    (let [handler  (wrap-compression mock-handler)
          request  {:headers {"Accept-Encoding" "deflate"}}
          response (handler request)]
      (is (= "deflate" (get-in response [:headers "Content-Encoding"])))
      (is (= "content content content content"
             (with-open [out (ByteArrayOutputStream.)]
               (protos/write-body-to-stream (:body response) response out)
               (slurp (InflaterInputStream. (ByteArrayInputStream. (.toByteArray out))))))))))


(deftest wrap-caching-test
  (testing "caching of brotli output"
    (let [handler (wrap-response-caching
                    (wrap-compression
                      (let [state (atom true)]
                        (fn [request]
                          (if (swap! state not)
                            (throw (ex-info "Error!" {}))
                            {:status  200
                             :headers {"Content-Type" "text/plain"}
                             :body    (ByteArrayInputStream. (.getBytes "content content content content"))})))))
          request {:headers        {"accept-encoding" "br"}
                   :request-method :get :uri "/"}]
      (let [response (handler request)]
        (is (= "content content content content"
               (with-open [out (ByteArrayOutputStream.)]
                 (protos/write-body-to-stream (:body response) response out)
                 (slurp (BrotliInputStream. (ByteArrayInputStream. (.toByteArray out))))))))
      (let [response2 (handler request)]
        (is (= "content content content content"
               (with-open [out (ByteArrayOutputStream.)]
                 (protos/write-body-to-stream (:body response2) response2 out)
                 (slurp (BrotliInputStream. (ByteArrayInputStream. (.toByteArray out)))))))))))