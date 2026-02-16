(ns parts.mcp.protocol
  (:require
   [parts.mcp.json-rpc :as jsonrpc]
   [parts.mcp.session :as session]))

(defmulti handle-request (fn [w] (get-in w [:mcp/rpc-request :method])))
;; JSON-RPC responses don't carry :method, so response correlation requires
;; tracking outbound requests by ID. For now, handle-response is a no-op.
;; Extend this when outbound request tracking is added.
(defmulti handle-response (fn [w] ::default))
(defmulti handle-notification (fn [w] (get-in w [:mcp/rpc-request :method])))

(defmethod handle-request "initialize" [w]
  (let [{:keys [params id]} (:mcp/rpc-request w)
        {:keys [protocolVersion capabilities clientInfo]} params
        session-id (:mcp/session-id w)
        sessions (:mcp/sessions w)]
    (session/create-session! sessions session-id clientInfo)
    (session/update-session! sessions session-id
                             assoc
                             :protocol-version protocolVersion
                             :client-capabilities capabilities)
    (jsonrpc/response
     id
     {:protocolVersion (:mcp/protocol-version w "2025-06-18")
      :capabilities (or (:mcp/capabilities w)
                        {:logging {}
                         :prompts {:listChanged true}
                         :resources {:listChanged true}
                         :tools {:listChanged true}})
      :serverInfo (:mcp/server-info w)})))

(defmethod handle-request "logging/setLevel" [w]
  (let [{:keys [id params]} (:mcp/rpc-request w)]
    (session/update-session! (:mcp/sessions w) (:mcp/session-id w)
                             assoc :logging params)
    (jsonrpc/response id {})))

(defmethod handle-request "tools/list" [w]
  (let [{:keys [id]} (:mcp/rpc-request w)
        entries ((:system/get-register w))
        tools (->> entries
                   (filter #(= :mcp/tool (:mcp/type %)))
                   (mapv :mcp/tool))]
    (jsonrpc/response id {:tools tools})))

(defmethod handle-request "tools/call" [w]
  (let [{:keys [id params]} (:mcp/rpc-request w)
        {:keys [name arguments]} params
        entries ((:system/get-register w))
        tool (some #(when (and (= :mcp/tool (:mcp/type %))
                               (= name (get-in % [:mcp/tool :name])))
                      %)
                   entries)]
    (if tool
      (try
        (jsonrpc/response id ((:mcp/handler tool) w arguments))
        (catch Exception e
          (jsonrpc/error id {:code jsonrpc/internal-error
                             :message (.getMessage e)})))
      (jsonrpc/error id {:code jsonrpc/invalid-params
                         :message (str "Tool not found: " name)}))))

(defmethod handle-request "prompts/list" [w]
  (let [{:keys [id]} (:mcp/rpc-request w)
        entries ((:system/get-register w))
        prompts (->> entries
                     (filter #(= :mcp/prompt (:mcp/type %)))
                     (mapv :mcp/prompt))]
    (jsonrpc/response id {:prompts prompts})))

(defmethod handle-request "prompts/get" [w]
  (let [{:keys [id params]} (:mcp/rpc-request w)
        {:keys [name arguments]} params
        entries ((:system/get-register w))
        prompt (some #(when (and (= :mcp/prompt (:mcp/type %))
                                 (= name (get-in % [:mcp/prompt :name])))
                        %)
                     entries)]
    (if prompt
      (try
        (let [messages ((:mcp/handler prompt) w arguments)]
          (jsonrpc/response id {:description (get-in prompt [:mcp/prompt :description])
                                :messages messages}))
        (catch Exception e
          (jsonrpc/error id {:code jsonrpc/internal-error
                             :message (.getMessage e)})))
      (jsonrpc/error id {:code jsonrpc/invalid-params
                         :message (str "Prompt not found: " name)}))))

(defmethod handle-request "resources/list" [w]
  (let [{:keys [id]} (:mcp/rpc-request w)
        entries ((:system/get-register w))
        resources (->> entries
                       (filter #(= :mcp/resource (:mcp/type %)))
                       (mapv :mcp/resource))]
    (jsonrpc/response id {:resources resources})))

(defmethod handle-request "resources/read" [w]
  (let [{:keys [id params]} (:mcp/rpc-request w)
        uri (:uri params)
        entries ((:system/get-register w))
        resource (some #(when (and (= :mcp/resource (:mcp/type %))
                                   (= uri (get-in % [:mcp/resource :uri])))
                          %)
                       entries)]
    (if resource
      (try
        (jsonrpc/response id ((:mcp/handler resource) w))
        (catch Exception e
          (jsonrpc/error id {:code jsonrpc/internal-error
                             :message (.getMessage e)})))
      (jsonrpc/error id {:code jsonrpc/invalid-params
                         :message (str "Resource not found: " uri)}))))

(defmethod handle-request "resources/templates/list" [w]
  (let [{:keys [id]} (:mcp/rpc-request w)
        entries ((:system/get-register w))
        templates (->> entries
                       (filter #(= :mcp/resource-template (:mcp/type %)))
                       (mapv :mcp/resource-template))]
    (jsonrpc/response id {:resourceTemplates templates})))

(defmethod handle-request :default [w]
  (let [{:keys [id method]} (:mcp/rpc-request w)]
    (jsonrpc/error id {:code jsonrpc/method-not-found
                       :message (str "Method not found: " method)})))

(defmethod handle-notification "notifications/initialized" [w]
  (let [sessions (:mcp/sessions w)
        session-id (:mcp/session-id w)]
    (session/update-session! sessions session-id assoc :initialized? true)))

(defmethod handle-notification "notifications/cancelled" [_w]
  nil)

(defmethod handle-notification :default [_w]
  nil)

(defmethod handle-response ::default [_w]
  nil)
