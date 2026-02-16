(ns parts.mcp.session)

(defn create-session! [sessions-atom session-id client-info]
  (swap! sessions-atom assoc session-id
         {:client-info client-info
          :sse-connections {}
          :initialized? false}))

(defn get-session [sessions-atom session-id]
  (get @sessions-atom session-id))

(defn session-exists? [sessions-atom session-id]
  (contains? @sessions-atom session-id))

(defn destroy-session! [sessions-atom session-id]
  (let [old-sessions (first (swap-vals! sessions-atom dissoc session-id))
        session (get old-sessions session-id)]
    (when session
      (doseq [[_ {:keys [close!]}] (:sse-connections session)]
        (when close! (close!))))))

(defn add-sse-connection! [sessions-atom session-id conn-id send! close!]
  (swap! sessions-atom assoc-in
         [session-id :sse-connections conn-id]
         {:send! send! :close! close!}))

(defn remove-sse-connection! [sessions-atom session-id conn-id]
  (swap! sessions-atom update-in
         [session-id :sse-connections] dissoc conn-id))

(defn send-to-session! [sessions-atom session-id message]
  (when-let [session (get @sessions-atom session-id)]
    (doseq [[_ {:keys [send!]}] (:sse-connections session)]
      (when send! (send! message)))))

(defn broadcast-to-all-sessions! [sessions-atom message]
  (doseq [[session-id _] @sessions-atom]
    (send-to-session! sessions-atom session-id message)))

(defn update-session! [sessions-atom session-id f & args]
  (apply swap! sessions-atom update session-id f args))
