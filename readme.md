[![.github/workflows/test.yaml](https://github.com/RutledgePaulV/ring-compression/actions/workflows/test.yaml/badge.svg?branch=master)](https://github.com/RutledgePaulV/ring-compression/actions/workflows/test.yaml)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.rutledgepaulv/ring-compression.svg)](https://clojars.org/io.github.rutledgepaulv/ring-compression)

<img src="./docs/logo.png" title="ring-compression" width="300" height="300" align="left" padding="5px"/>
<p>
<br/><br/><br/><br/><br/>
Clojure middleware for response encoding negotiation and compression. Natively supports gzip
and deflate compression. Support for brotli can be enabled by including <a href="#enabling-brotli">another library</a>
on your classpath.
</p>
<br clear="all" /><br />


---

### Rationale

There are other Clojure libraries that offer gzip compression, but they don't perform encoding negotiation. The
libraries I'm aware of also spawn an extra thread per request to pump a pair of piped streams. That extra thread can be
avoided through use of a custom implementation of ring's `StreamableResponseBody` protocol as implemented here.

---

### Basic Usage

This library provides reasonable defaults so most users should benefit from using the middleware with no additional
configuration. You should place this middleware around the exterior of your application. Be aware that the body in the
response map when compression applies will be a reification of the `StreamableResponseBody` protocol and less tangible
than an input stream / byte array / string as you may expect in existing tests.

```clojure

(require '[ring.adapter.jetty :as jetty])
(require '[ring-compression.core :as compress])

(defn application [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "content content content content"})

(def wrapped (compress/wrap-compression application))

(jetty/run-jetty wrapped {:port 3000 :join? false})

```

### Advanced Usage

You can override the available compressors and their server-side priority for various content types. Negotiation will
always try to honor a client's preference first, but if a client indicates no preference between multiple algorithms the
server-side priorities will be leveraged to break the tie.

```clojure 

(require '[ring.adapter.jetty :as jetty])
(require '[ring-compression.core :as compress])

; the priority numbers are just used as a ranking within the list
; and are never compared to priority numbers presented by a client

(def gzip-over-deflate 
    [{:algorithm "gzip" :priority 1.0}
     {:algorithm "deflate" :priority 0.9}])
     
(def deflate-over-gzip
    [{:algorithm "gzip" :priority 0.9}
     {:algorithm "deflate" :priority 1.0}])

(def server-preferences 
    {; all text mime types will prefer gzip over deflate
     ; if the client includes both with equal priority
     "text/*"        gzip-over-deflate
     
     ; all application mime types will prefer deflate over gzip
     ; if the client includes both with equal priority
     "application/*" deflate-over-gzip
     
     ; everything else will not support compression even
     ; if the client indicates a preference for it
     "*"             []})

; same as before
(defn application [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "content content content content"})

; supply options for the middleware this time
(def options {:server-preferences preferences})
    
(def wrapped (compress/wrap-compression application options))

(jetty/run-jetty wrapped {:port 3000 :join? false})

```

### Enabling Brotli

You can include [another dependency](https://github.com/nixxcode/jvm-brotli) in your project to enable brotli. Brotli is
an order of magnitude slower to compress than gzip, but it has higher compression ratios and is just as quick as gzip to
decompress. It is therefore better for clients but not as good for on the fly compression. You are probably better off 
sticking to gzip, compressing your static textual assets with brotli ahead of time, or configuring a layer of caching 
as well.

If present, brotli will be used (by default) on any html/css/javascript responses but gzip will be used on more latency 
sensitive edn/json/transit/xml responses.

```clojure

com.nixxcode.jvmbrotli/jvmbrotli {:mvn/version "0.2.0"}

```

---

### Alternatives

- https://github.com/bertrandk/ring-gzip
- https://github.com/clj-commons/ring-gzip-middleware
- https://martintrojer.github.io/clojure/2015/10/04/enable-gzip-with-ring-and-jetty

---

### License

This project is licensed under [MIT license](http://opensource.org/licenses/MIT).
