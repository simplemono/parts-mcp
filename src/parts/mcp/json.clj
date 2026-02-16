(ns parts.mcp.json
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn read-str [s]
  (json/parse-string s keyword))

(defn write-str [data]
  (json/generate-string data))

(defn read-stream [input-stream]
  (json/parse-stream (io/reader input-stream) keyword))
