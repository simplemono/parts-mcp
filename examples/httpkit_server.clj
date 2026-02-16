(ns httpkit-server
  "Example MCP server using parts.httpkit with SSE support.

  Run with: clj -M:example -m httpkit-server"
  (:require [parts.mcp :as mcp]
            [parts.ring.route]
            [parts.httpkit.server :as server]))

;; Define tools as data

(def tools
  [(mcp/tool {:name "greet"
              :description "Sends a personalized greeting"
              :malli [:map [:name string?]]
              :handler (fn [_w {:keys [name]}]
                         {:content [{:type "text" :text (str "Hello, " name "!")}]
                          :isError false})})

   (mcp/tool {:name "add"
              :description "Adds two numbers together"
              :malli [:map [:a number?] [:b number?]]
              :handler (fn [_w {:keys [a b]}]
                         {:content [{:type "text" :text (str (+ a b))}]
                          :isError false})})])

;; Define prompts as data

(def prompts
  [(mcp/prompt {:name "joke-rating"
                :description "Rate how funny a joke is"
                :arguments [{:name "joke" :description "The joke to rate" :required true}]
                :handler (fn [_w {:keys [joke]}]
                           [{:role "user"
                             :content {:type "text"
                                       :text (str "Rate this joke from 1-5:\n\n" joke)}}])})])

;; Define resources as data

(def resources
  [(mcp/resource {:uri "info://server"
                  :name "Server Info"
                  :description "Information about this MCP server"
                  :mimeType "text/plain"
                  :handler (fn [_w]
                             {:contents [{:uri "info://server"
                                          :text "This is an example MCP server built with parts.mcp"}]})})])

;; Session state - user-provided atom

(def sessions (atom {}))

;; Registration function - concatenates all entries

(defn get-register []
  (concat tools
          prompts
          resources
          (mcp/routes)
          parts.ring.route/register))

;; System / world map

(def system
  (atom {:system/get-register #'get-register
         :mcp/sessions sessions
         :mcp/server-info {:name "Example MCP Server" :version "1.0.0"}
         :mcp/capabilities mcp/default-capabilities
         :mcp/protocol-version mcp/protocol-version}))

;; Start the server

(defn -main [& _args]
  (server/start! system {:port 3999})
  (println "MCP server started on http://localhost:3999/mcp"))

(comment
  (server/start! system {:port 3999})
  (server/stop! system))
