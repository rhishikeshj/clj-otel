(ns example.manual-instrument.middleware.random-word-service
  "Example application demonstrating using `clj-otel` to add telemetry to a
  synchronous Ring HTTP service that is run without the OpenTelemetry
  instrumentation agent."
  (:require [example.common-utils.middleware :as middleware]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.util.response :as response]
            [steffan-westcott.clj-otel.api.trace.http :as trace-http]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [steffan-westcott.clj-otel.instrumentation.runtime-metrics :as runtime-metrics]))


(def words
  "Map of word types and collections of random words of that type."
  {:noun      ["amusement" "bat" "cellar" "engine" "flesh" "frogs" "hearing" "record"]
   :verb      ["afford" "behave" "ignite" "justify" "race" "sprout" "strain" "wake"]
   :adjective ["cultured" "glorious" "grumpy" "handy" "kind" "lush" "mixed" "shut"]})



(defn random-word
  "Gets a random word of the requested type."
  [word-type]

  ;; Wrap the synchronous body in a new internal span.
  (span/with-span! {:name       "Generating word"
                    :attributes {:system/word-type word-type}}

    (Thread/sleep (+ 10 (rand-int 80)))

    ;; Simulate an intermittent runtime exception.
    ;; An uncaught exception leaving a span's scope is reported as an
    ;; exception event and the span status description is set to the
    ;; exception triage summary.
    (when (= :fault word-type)
      (throw (RuntimeException. "Processing fault")))

    (let [candidates (or (get words word-type)

                         ;; Exception data is added as attributes to the
                         ;; exception event by default.
                         (throw
                          (ex-info "Unknown word type"
                                   {:http.response/status 400
                                    :service/error :service.random-word.errors/unknown-word-type
                                    :system/word-type word-type})))

          word       (rand-nth candidates)]

      ;; Add more attributes to the internal span
      (span/add-span-data! {:attributes {:system/word word}})

      word)))



(defn get-random-word-handler
  "Synchronous Ring handler for 'GET /random-word' request. Returns an HTTP
  response containing a random word of the requested type."
  [{:keys [query-params]}]

  ; Add attributes describing matched route to server span
  (trace-http/add-route-data! "/random-word")

  (let [type   (keyword (get query-params "type"))
        result (random-word type)]
    (Thread/sleep (+ 20 (rand-int 20)))
    (response/response (str result))))



(defn handler
  "Synchronous Ring handler for all requests."
  [{:keys [request-method uri]
    :as   request}]
  (case [request-method uri]
    [:get "/random-word"] (get-random-word-handler request)
    (response/not-found "Not found")))



(def service
  "Ring handler with middleware applied."
  (-> handler
      params/wrap-params
      middleware/wrap-exception

      ;; Wrap request handling of all routes. As this application is not run
      ;; with the OpenTelemetry instrumentation agent, create a server span
      ;; for each request.
      (trace-http/wrap-server-span {:create-span? true
                                    :server-name  "random"})))


;; Register measurements that report metrics about the JVM runtime. These measurements cover
;; buffer pools, classes, CPU, garbage collector, memory pools and threads.
(runtime-metrics/register!)


(defonce ^{:doc "random-word-service server instance"} server
         (jetty/run-jetty #'service
                          {:port  8081
                           :join? false}))
