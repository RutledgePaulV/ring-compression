<img src="./docs/logo.png" title="ring-compression" width="300" height="300" align="left" padding="5px"/>
<small>
<br/><br/><br/><br/><br/>
Clojure middleware for response encoding negotiation and compression. Natively supports gzip
and deflate compression. Support for brotli can be enabled by including a yet-to-be-released 
version of <a href="https://github.com/google/brotli">brotli</a> on your classpath.
</small>
<br clear="all" /><br />


---

### Rationale

There are other Clojure libraries that offer gzip compression, but they don't perform encoding negotiation. The
libraries I'm aware of also spawn an extra thread per request to pump a pair of piped streams. That extra thread can be
avoided through use of a custom implementation of ring's `StreamableResponseBody` protocol.

The performance overhead is quite small at ~50μs above not using the middleware at all.

---

### Basic Usage

This library provides reasonable defaults so most users should benefit from using the middleware with no additional
configuration.

```clojure

(defn application [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "content content content content"})

(def wrapped (wrap-compression application))

(jetty/run-jetty wrapped {:port 3000 :join? false})

```

---

### License

This project is licensed under [MIT license](http://opensource.org/licenses/MIT).
