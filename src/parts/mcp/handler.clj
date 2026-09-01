(ns parts.mcp.handler
  (:require
   [parts.mcp.json :as json]
   [parts.mcp.json-rpc :as jsonrpc]
   [parts.mcp.protocol :as protocol]
   [parts.mcp.session :as session]
   [clojure.string :as str]))

(defn- accepts-sse? [request]
  (some-> (get-in request [:headers "accept"])
          (str/includes? "text/event-stream")))

(defn- session-id-from-request [request]
  (get-in request [:headers "mcp-session-id"]))

(defn- process-message [w msg]
  (let [w (assoc w :mcp/rpc-request msg)]
    (cond
      (jsonrpc/request? msg)
      (protocol/handle-request w)

      (jsonrpc/notification? msg)
      (do (protocol/handle-notification w) nil)

      (jsonrpc/response? msg)
      (do (protocol/handle-response w) nil)

      :else
      (when (:id msg)
        (jsonrpc/error (:id msg) {:code jsonrpc/invalid-request
                                  :message "Invalid JSON-RPC message"})))))

(defn post-handler
  "POST /mcp - Main MCP Streamable HTTP endpoint.
   Handles JSON-RPC requests, notifications, and responses."
  [w]
  (let [request (:ring/request w)
        body (try
               (json/read-stream (:body request))
               (catch Exception _
                 ::parse-error))]
    (if (= body ::parse-error)
      (assoc w :ring/response
             {:status 400
              :headers {"Content-Type" "application/json"}
              :body (json/write-str (jsonrpc/error nil {:code jsonrpc/parse-error
                                                        :message "Parse error"}))})
      (let [session-id (session-id-from-request request)
            sessions (:mcp/sessions w)
            messages (if (sequential? body) body [body])
            is-initialize? (some #(= "initialize" (:method %)) messages)]

        (cond
          ;; Must have session ID unless initializing
          (and (not session-id) (not is-initialize?))
          (assoc w :ring/response
                 {:status 400
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str {:error "Missing Mcp-Session-Id header"})})

          ;; Can't re-initialize existing session
          (and session-id is-initialize?)
          (assoc w :ring/response
                 {:status 400
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str {:error "Cannot re-initialize existing session"})})

          ;; Session must exist (unless initializing)
          (and session-id (not (session/session-exists? sessions session-id)))
          (assoc w :ring/response
                 {:status 404
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str {:error (str "Session not found: " session-id)})})

          :else
          (let [;; For initialize, generate a new session ID
                session-id (or session-id (str (random-uuid)))
                w (assoc w :mcp/session-id session-id)
                requests (filter jsonrpc/request? messages)
                non-requests (remove jsonrpc/request? messages)]

            ;; Process notifications and responses fire-and-forget
            (doseq [msg non-requests]
              (process-message w msg))

            (if (empty? requests)
              ;; No requests, just notifications/responses
              (assoc w :ring/response {:status 202 :headers {} :body ""})

              ;; Has requests - check if we should use SSE or JSON response
              (if (and (:ring/sse-send! w) (accepts-sse? request))
                ;; SSE mode
                (let [respond (:ring/respond w)
                      sse-send! (:ring/sse-send! w)
                      sse-close! (:ring/sse-close! w)
                      conn-id (str (random-uuid))]
                  (respond {:status 200
                            :headers {"Content-Type" "text/event-stream"
                                      "Cache-Control" "no-cache"
                                      "Connection" "keep-alive"
                                      "Mcp-Session-Id" session-id}})
                  (session/add-sse-connection!
                   sessions session-id conn-id sse-send! sse-close!)
                  ;; Process each request and send response as SSE event
                  (doseq [req requests]
                    (let [response (process-message w req)]
                      (when response
                        (sse-send! {:event "message"
                                    :data (json/write-str response)}))))
                  ;; Per the MCP Streamable HTTP spec, the server SHOULD
                  ;; close the SSE stream after all responses have been
                  ;; sent. Server-initiated messages go over the GET
                  ;; stream instead.
                  (session/remove-sse-connection! sessions session-id conn-id)
                  (when sse-close!
                    (sse-close!))
                  ;; Return w without :ring/response to signal async
                  w)

                ;; JSON mode
                (let [responses (doall
                                 (keep #(process-message w %) requests))
                      result (if (= 1 (count responses))
                               (first responses)
                               responses)]
                  (assoc w :ring/response
                         {:status 200
                          :headers {"Content-Type" "application/json"
                                    "Mcp-Session-Id" session-id}
                          :body (json/write-str result)}))))))))))

(defn get-handler
  "GET /mcp - SSE notification stream.
   Opens a persistent SSE connection for server-initiated notifications."
  [w]
  (let [request (:ring/request w)
        session-id (session-id-from-request request)
        sessions (:mcp/sessions w)]
    (cond
      (not session-id)
      (assoc w :ring/response
             {:status 400
              :headers {"Content-Type" "application/json"}
              :body (json/write-str {:error "Missing Mcp-Session-Id header"})})

      (not (session/session-exists? sessions session-id))
      (assoc w :ring/response
             {:status 404
              :headers {"Content-Type" "application/json"}
              :body (json/write-str {:error (str "Session not found: " session-id)})})

      (not (accepts-sse? request))
      (assoc w :ring/response
             {:status 400
              :headers {"Content-Type" "application/json"}
              :body (json/write-str {:error "GET /mcp requires Accept: text/event-stream"})})

      (not (:ring/sse-send! w))
      (assoc w :ring/response
             {:status 500
              :headers {"Content-Type" "application/json"}
              :body (json/write-str {:error "SSE not supported by server adapter"})})

      :else
      (let [respond (:ring/respond w)
            sse-send! (:ring/sse-send! w)
            sse-close! (:ring/sse-close! w)
            conn-id (str (random-uuid))]
        (respond {:status 200
                  :headers {"Content-Type" "text/event-stream"
                            "Cache-Control" "no-cache"
                            "Connection" "keep-alive"
                            "Mcp-Session-Id" session-id}})
        (session/add-sse-connection!
         sessions session-id conn-id sse-send! sse-close!)
        ;; Return w without :ring/response to signal async
        w))))

(defn delete-handler
  "DELETE /mcp - Session termination.
   Destroys the session and closes all SSE connections."
  [w]
  (let [request (:ring/request w)
        session-id (session-id-from-request request)
        sessions (:mcp/sessions w)]
    (cond
      (not session-id)
      (assoc w :ring/response
             {:status 400
              :headers {"Content-Type" "application/json"}
              :body (json/write-str {:error "Missing Mcp-Session-Id header"})})

      (not (session/session-exists? sessions session-id))
      (assoc w :ring/response
             {:status 404
              :headers {"Content-Type" "application/json"}
              :body (json/write-str {:error (str "Session not found: " session-id)})})

      :else
      (do
        (session/destroy-session! sessions session-id)
        (assoc w :ring/response
               {:status 200
                :headers {"Content-Type" "application/json"}
                :body (json/write-str {:ok true})})))))
