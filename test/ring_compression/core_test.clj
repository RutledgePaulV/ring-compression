(ns ring-compression.core-test
  (:require [clojure.test :refer :all]
            [ring-compression.core :refer :all]))

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