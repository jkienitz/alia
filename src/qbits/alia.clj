(ns qbits.alia
  (:require
   [qbits.knit :as knit]
   [qbits.alia.codec :as codec]
   [qbits.alia.utils :as utils]
   [qbits.alia.enum :as enum]
   [qbits.hayt :as hayt]
   [lamina.core :as l]
   [clojure.core.memoize :as memo]
   [clojure.core.async :as async]
   [qbits.alia.cluster-options :as copt])
  (:import
   (com.datastax.driver.core
    BoundStatement
    Cluster
    Cluster$Builder
    PreparedStatement
    Statement
    ResultSet
    ResultSetFuture
    Session
    SimpleStatement
    Statement)
   (com.google.common.util.concurrent
    Futures
    FutureCallback)
   (java.nio ByteBuffer)))

(def default-executor (delay (knit/executor :cached)))
(def hayt-query-fn (memo/lu hayt/->raw :lu/threshold 100))

(def set-hayt-query-fn!
  "Sets root value of hayt-query-fn, allowing to control how hayt
    queries are executed , defaults to LU with a threshold of 100,
    this is a global var, changing it will impact all threads"
  (utils/var-root-setter hayt-query-fn))

(defn cluster
  "Takes an option map and returns a new
com.datastax.driver.core/Cluster instance.

The following options are supported:

* `:contact-points`: a list of nodes ip addresses to connect to.

* `:port`: port to connect to on the nodes (native transport must be
  active on the nodes: `start_native_transport: true` in
  cassandra.yaml). Defaults to 9042 if not supplied.

* `:load-balancing-policy`: Configure the
  [Load Balancing Policy](http://mpenet.github.io/alia/qbits.alia.policy.load-balancing.html)
  to use for the new cluster.

* `:reconnection-policy`: Configure the
  [Reconnection Policy](http://mpenet.github.io/alia/qbits.alia.policy.reconnection.html)
  to use for the new cluster.

* `:retry-policy`: Configure the
  [Retry Policy](http://mpenet.github.io/alia/qbits.alia.policy.retry.html)
  to use for the new cluster.

* `:metrics?`: Toggles metrics collection for the created cluster
  (metrics are enabled by default otherwise).

* `:jmx-reporting?`: Toggles JMX reporting of the metrics.

* `:credentials`: Takes a map of :username and :password for use with
  Cassandra's PasswordAuthenticator

* `:compression`: Compression supported by the Cassandra binary
  protocol. Can be `:none` or `:snappy`.

* `:ssl?`: enables/disables SSL

* `:ssl-options`: advanced SSL setup using a
  `com.datastax.driver.core.SSLOptions` instance

* `:pooling-options`: The pooling options used by this builder.
  Options related to connection pooling.

  The driver uses connections in an asynchronous way. Meaning that
  multiple requests can be submitted on the same connection at the
  same time. This means that the driver only needs to maintain a
  relatively small number of connections to each Cassandra host. These
  options allow to control how many connections are kept exactly.

  For each host, the driver keeps a core amount of connections open at
  all time. If the utilisation of those connections reaches a
  configurable threshold ,more connections are created up to a
  configurable maximum number of connections.

  Once more than core connections have been created, connections in
  excess are reclaimed if the utilisation of opened connections drops
  below the configured threshold.

  Each of these parameters can be separately set for `:local` and `:remote`
  hosts (HostDistance). For `:ignored` hosts, the default for all those
  settings is 0 and cannot be changed.

  Each of the following configuration keys, take a map of {distance value}  :
  ex:
  ```clojure
  :core-connections-per-host {:remote 10 :local 100}
  ```

  + `:core-connections-per-host`
  + `:max-connections-per-host`
  + `:max-simultaneous-requests-per-connection`
  + `:min-simultaneous-requests-per-connection`

* `:socket-options`: a map of
  + connect-timeout-millis (number)
  + read-timeout-millis (number)
  + receive-buffer-size (number)
  + send-buffer-size (number)
  + so-linger (number)
  + tcp-no-delay? (bool)
  + reuse-address? (bool)
  + keep-alive? (bool)

* `:query-options`: a map of
  + fetch-size (number)
  + consistency (consistency keyword)
  + serial-consistency (consistency keyword)

* `:jmx-reporting?`: enables/disables JMX reporting of the metrics.



The handling of these options is achieved with a multimethod that you
could extend if you need to handle some special case or want to create
your own options templates.
See `qbits.alia.cluster-options/set-cluster-option!` [[source]](../src/qbits/alia/cluster_options.clj#L19)
"
  ([options]
     (-> (Cluster/builder)
         (copt/set-cluster-options! (merge {:contact-points ["localhost"]}
                                           options))
         .build))
  ([] (cluster {})))

(defn ^Session connect
  "Returns a new com.datastax.driver.core/Session instance. We need to
have this separate in order to allow users to connect to multiple
keyspaces from a single cluster instance"
  ([^Cluster cluster keyspace]
     (.connect cluster (name keyspace)))
  ([^Cluster cluster]
     (.connect cluster)))

(defn shutdown
  "Shutdowns Session or Cluster instance, clearing the underlying
pools/connections"
  [^java.io.Closeable c]
  (.closeAsync c))

(defn ^:private ex->ex-info
  ([^Exception ex data msg]
     (ex-info msg
              (merge {:type ::execute
                      :exception ex}
                     data)
              (.getCause ex)))
  ([ex data]
     (ex->ex-info ex data "Query execution failed")))

(defn prepare
  "Takes a session and a query (raw string or hayt) and returns a
  com.datastax.driver.core.PreparedStatement instance to be used in
  `execute` after it's been bound with `bind`. Hayt query parameter
  will be compiled with qbits.hayt/->raw internaly
  ex: (prepare session (select :foo (where {:bar ?})))"
  [^Session session query]
  (let [^String q (if (map? query)
                    (hayt/->raw query)
                    query)]
    (try
      (.prepare session q)
      (catch Exception ex
        (throw (ex->ex-info ex
                            {:type ::prepare-error
                             :query q}
                            "Query prepare failed"))))))

(defn bind
  "Takes a statement and a collection of values and returns a
  com.datastax.driver.core.BoundStatement instance to be used with
  `execute` (or one of its variants)"
  [^PreparedStatement statement values]
  (try
    (.bind statement (to-array (map codec/encode values)))
    (catch Exception ex
      (throw (ex->ex-info ex {:query statement
                              :type ::bind-error
                              :values values}
                          "Query binding failed")))))

(defprotocol PStatement
  (^:no-doc query->statement
    [q values] "Encodes input into a Statement instance"))

(extend-protocol PStatement
  Statement
  (query->statement [q _] q)

  PreparedStatement
  (query->statement [q values]
    (bind q values))

  String
  (query->statement [q _]
    (SimpleStatement. q))

  clojure.lang.IPersistentMap
  (query->statement [q _]
    (query->statement (hayt-query-fn q) nil)))

(defn ^:private set-statement-options!
  [^Statement statement routing-key retry-policy tracing? consistency
   serial-consistency fetch-size]
  (when routing-key
    (.setRoutingKey ^SimpleStatement statement
                    ^ByteBuffer routing-key))
  (when retry-policy
    (.setRetryPolicy statement retry-policy))
  (when tracing?
    (.enableTracing statement))
  (when fetch-size
    (.setFetchSize statement fetch-size))
  (when serial-consistency
    (.setSerialConsistencyLevel statement
                                (enum/consistency-levels serial-consistency)))
  (when consistency
    (.setConsistencyLevel statement (enum/consistency-levels consistency))))

(defn execute
  "Executes a query against a session. Returns a collection of rows.
The query can be a raw string, a PreparedStatement (returned by
`prepare`) with values passed via the `:values` option key will be bound by
`execute`, BoundStatement (returned by `qbits.alia/bind`), or a Hayt query."
  ([^Session session query {:keys [consistency serial-consistency
                                   routing-key retry-policy tracing? string-keys?
                                   fetch-size values]}]
     (let [^Statement statement (query->statement query values)]
       (set-statement-options! statement routing-key retry-policy tracing?
                               consistency serial-consistency fetch-size)
       (try
         (codec/result-set->maps (.execute session statement) string-keys?)
         (catch Exception err
           (throw (ex->ex-info err {:query statement :values values}))))))
  ;; to support old syle api with unrolled args
  ([^Session session query]
     (execute session query {})))

(defn execute-async
  "Same as execute, but returns a promise and accepts :success and :error
  handlers via options, you can also pass :executor for the ResultFuture, it
  defaults to a cachedThreadPool if you don't"
  ([^Session session query {:keys [success error executor consistency
                                   serial-consistency routing-key
                                   retry-policy tracing? string-keys? fetch-size
                                   values]}]
     (let [^Statement statement (query->statement query values)]
       (set-statement-options! statement routing-key retry-policy tracing?
                               consistency serial-consistency fetch-size)
       (let [^ResultSetFuture rs-future
             (try
               (.executeAsync session statement)
               (catch Exception ex
                 (throw (ex->ex-info ex {:query statement :values values}))))
             async-result (l/result-channel)]
         (l/on-realized async-result success error)
         (Futures/addCallback
          rs-future
          (reify FutureCallback
            (onSuccess [_ result]
              (l/success async-result
                         (codec/result-set->maps (.get rs-future) string-keys?)))
            (onFailure [_ ex]
              (l/error async-result
                       (ex->ex-info ex {:query statement :values values}))))
          (or executor @default-executor))
         async-result)))
    ([^Session session query]
       (execute-async session query {})))

(defn execute-chan
  "Same as execute, but returns a clojure.core.async/chan that is
  wired to the underlying ResultSetFuture. This means this is usable
  with `go` blocks or `take!`. Exceptions are sent to the
  channel as a value, it's your responsability to handle these how you
  deem appropriate."
  ([^Session session query {:keys [executor consistency serial-consistency
                                   routing-key retry-policy tracing?
                                   string-keys? fetch-size values]}]
     (let [^Statement statement (query->statement query values)]
       (set-statement-options! statement routing-key retry-policy tracing?
                               consistency serial-consistency fetch-size)
       (let [^ResultSetFuture rs-future (.executeAsync session statement)
             ch (async/chan 1)]
         (Futures/addCallback
          rs-future
          (reify FutureCallback
            (onSuccess [_ result]
              (async/put! ch (codec/result-set->maps (.get rs-future) string-keys?))
              (async/close! ch))
            (onFailure [_ ex]
              (async/put! ch (ex->ex-info ex {:query statement :values values}))
              (async/close! ch)))
          (or executor @default-executor))
         ch)))
  ([^Session session query]
     (execute-chan session query {})))

(defn ^:private lazy-query-
  [session query pred coll opts]
  (lazy-cat coll
            (when query
              (let [coll (execute session query opts)]
                (lazy-query- session (pred query coll) pred coll opts)))))

(defn lazy-query
  "Takes a session, a query (hayt, raw or prepared) and a query modifier fn (that
receives the last query and last chunk and returns a new query or nil).
The first chunk will be the original query result, then for each
subsequent chunk the query will be the result of last query
modified by the modifier fn unless the fn returns nil,
which would causes the iteration to stop.

It also accepts `execute` options.

ex: (lazy-query session
                (select :items (limit 2) (where {:x (int 1)}))
                        (fn [q coll]
                          (merge q (where {:si (-> coll last :x inc)})))
                {:consistency :quorum :tracing? true})"
  ([session query pred opts]
     (lazy-query- session query pred [] opts))
  ([session query pred]
     (lazy-query session query pred {})))
