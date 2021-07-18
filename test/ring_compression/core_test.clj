(ns ring-compression.core-test
  (:require [clojure.test :refer :all]
            [ring-compression.core :refer :all]
            [ring.core.protocols :as protos]
            [clojure.string :as strings])
  (:import (java.io ByteArrayOutputStream ByteArrayInputStream)
           (java.util.zip GZIPInputStream InflaterInputStream)))

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
  (testing "gzip compression"
    (let [handler  (wrap-compression mock-handler)
          request  {:headers {"Accept-Encoding" "br, gzip, deflate"}}
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
