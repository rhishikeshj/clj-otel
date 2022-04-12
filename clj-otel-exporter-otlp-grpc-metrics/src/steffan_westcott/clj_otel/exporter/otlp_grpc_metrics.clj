(ns steffan-westcott.clj-otel.exporter.otlp-grpc-metrics
  "Metric data exporter using OpenTelemetry Protocol via gRPC."
  (:require [steffan-westcott.clj-otel.util :as util])
  (:import (io.opentelemetry.exporter.otlp.metrics OtlpGrpcMetricExporter
                                                   OtlpGrpcMetricExporterBuilder)
           (java.util.function Function)))

(defn- add-headers
  ^OtlpGrpcMetricExporterBuilder [builder headers]
  (reduce-kv #(.addHeader ^OtlpGrpcMetricExporterBuilder %1 %2 %3) builder headers))

(defn metric-exporter
  "Returns a metric data exporter that sends span data using OTLP via gRPC,
  using OpenTelemetry's protobuf model. May take an option map as follows:

  | key                       | description |
  |---------------------------|-------------|
  |`:endpoint`                | OTLP endpoint, must start with `\"http://\"` or `\"https://\"` (default: `\"http://localhost:4317\"`).
  |`:headers`                 | HTTP headers to add to request (default: `{}`).
  |`:trusted-certificates-pem`| `^bytes` X.509 certificate chain in PEM format for verifying servers when TLS enabled (default: system default trusted certificates).
  |`:client-private-key-pem`  | `^bytes` private key in PEM format for verifying client when TLS enabled.
  |`:client-certificates-pem` | `^bytes` X.509 certificate chain in PEM format for verifying client when TLS enabled.
  |`:compression-method`      | Method used to compress payloads, `\"gzip\"` or `\"none\"` (default: `\"none\"`).
  |`:timeout`                 | Maximum time to wait for export of a batch of spans. Value is either a `Duration` or a vector `[amount ^TimeUnit unit]` (default: 10s).
  |`:aggregation-temporality` | Function which takes an `InstrumentationType` and returns an `AggregationTemporality` (default: constantly `AggregationTemporality/CUMULATIVE`)."
  ([]
   (metric-exporter {}))
  ([{:keys [endpoint headers trusted-certificates-pem client-private-key-pem client-certificates-pem
            compression-method timeout aggregation-temporality]}]
   (let [builder (cond-> (OtlpGrpcMetricExporter/builder)
                   endpoint (.setEndpoint endpoint)
                   headers (add-headers headers)
                   trusted-certificates-pem (.setTrustedCertificates trusted-certificates-pem)
                   (and client-private-key-pem client-certificates-pem) (.setClientTls
                                                                         client-private-key-pem
                                                                         client-certificates-pem)
                   compression-method (.setCompression compression-method)
                   timeout (.setTimeout (util/duration timeout))
                   aggregation-temporality (.setAggregationTemporality
                                            (reify
                                             Function
                                               (apply [_ instrumentation]
                                                 (aggregation-temporality instrumentation)))))]
     (.build builder))))