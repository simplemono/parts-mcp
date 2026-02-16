(ns parts.mcp
  "Public API for MCP SDK built on parts.ring.

  Provides helper functions that produce registration entries (plain data maps)
  for tools, prompts, resources, and resource templates.

  Usage:
    (mcp/tool {:name \"greet\"
               :description \"Greets someone\"
               :malli [:map [:name string?]]
               :handler (fn [w args] {:content [{:type \"text\" :text \"Hi\"}]})})

    (mcp/routes)  ;; returns parts.ring route entries for /mcp"
  (:require
   [parts.mcp.handler :as handler]
   [parts.mcp.json :as json]
   [parts.mcp.json-rpc :as jsonrpc]
   [parts.mcp.session :as session]
   [malli.json-schema :as malli-json-schema]))

(def protocol-version "2025-06-18")

(def default-capabilities
  {:logging {}
   :prompts {:listChanged true}
   :resources {:listChanged true}
   :tools {:listChanged true}})

(defn tool
  "Creates a tool registration entry.

  Options:
    :name        - Tool name (required)
    :description - Tool description (required)
    :handler     - (fn [w args] -> {:content [...] :isError bool}) (required)
    :malli       - Malli schema for input (converted to JSON Schema)
    :schema      - Raw JSON Schema map (alternative to :malli)"
  [{:keys [name description handler malli schema]}]
  (let [input-schema (cond
                       malli (malli-json-schema/transform malli)
                       schema schema
                       :else {:type "object"})]
    {:mcp/type :mcp/tool
     :mcp/tool {:name name
                :description description
                :inputSchema input-schema}
     :mcp/handler handler}))

(defn prompt
  "Creates a prompt registration entry.

  Options:
    :name        - Prompt name (required)
    :description - Prompt description (required)
    :handler     - (fn [w args] -> [{:role ... :content ...}]) (required)
    :arguments   - Vector of argument descriptors"
  [{:keys [name description handler arguments]}]
  {:mcp/type :mcp/prompt
   :mcp/prompt (cond-> {:name name
                        :description description}
                 arguments (assoc :arguments arguments))
   :mcp/handler handler})

(defn resource
  "Creates a resource registration entry.

  Options:
    :uri         - Resource URI (required)
    :name        - Resource name (required)
    :description - Resource description
    :mimeType    - MIME type
    :handler     - (fn [w] -> {:contents [{:uri ... :text ...}]}) (required)"
  [{:keys [uri name description mimeType handler]}]
  {:mcp/type :mcp/resource
   :mcp/resource (cond-> {:uri uri :name name}
                   description (assoc :description description)
                   mimeType (assoc :mimeType mimeType))
   :mcp/handler handler})

(defn resource-template
  "Creates a resource template registration entry.

  Options:
    :uriTemplate - URI template (required)
    :name        - Template name (required)
    :description - Template description
    :mimeType    - MIME type
    :handler     - (fn [w uri] -> {:contents [...]}) (required)"
  [{:keys [uriTemplate name description mimeType handler]}]
  {:mcp/type :mcp/resource-template
   :mcp/resource-template (cond-> {:uriTemplate uriTemplate :name name}
                            description (assoc :description description)
                            mimeType (assoc :mimeType mimeType))
   :mcp/handler handler})

(defn routes
  "Returns parts.ring route registration entries for MCP endpoints.

  Options:
    :path - Base path (default \"/mcp\")"
  ([]
   (routes {}))
  ([{:keys [path] :or {path "/mcp"}}]
   [{:ring/route [:post path] :ring/handler #'handler/post-handler}
    {:ring/route [:get path] :ring/handler #'handler/get-handler}
    {:ring/route [:delete path] :ring/handler #'handler/delete-handler}]))

;; Notification helpers

(defn notify-tools-changed!
  "Sends tools/list_changed notification to all sessions."
  [sessions-atom]
  (session/broadcast-to-all-sessions!
   sessions-atom
   {:event "message"
    :data (json/write-str (jsonrpc/notification "notifications/tools/list_changed"))}))

(defn notify-prompts-changed!
  "Sends prompts/list_changed notification to all sessions."
  [sessions-atom]
  (session/broadcast-to-all-sessions!
   sessions-atom
   {:event "message"
    :data (json/write-str (jsonrpc/notification "notifications/prompts/list_changed"))}))

(defn notify-resources-changed!
  "Sends resources/list_changed notification to all sessions."
  [sessions-atom]
  (session/broadcast-to-all-sessions!
   sessions-atom
   {:event "message"
    :data (json/write-str (jsonrpc/notification "notifications/resources/list_changed"))}))
