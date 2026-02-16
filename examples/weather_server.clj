(ns weather-server
  "Weather MCP server example using the National Weather Service API.

  Run with: clj -M:example -m weather-server"
  (:require [parts.mcp :as mcp]
            [parts.ring.route]
            [parts.httpkit.server :as server]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as client]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP Requests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def NWS_API_BASE "https://api.weather.gov")
(def USER_AGENT "weather-app/1.0")
(def nws-headers {"User-Agent" USER_AGENT
                  "Accept" "application/geo+json"})

(defn get-nws
  [url]
  (client/get url
              {:headers nws-headers}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Processing API responses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn forecast->str
  [forecast]
  (str
   (get forecast "name") "\n"
   "Temperature: " (get forecast "temperature") (get forecast "temperatureUnit") "\n"
   "Wind: " (get forecast "windSpeed") "\n"
   "Forecast: " (get forecast "detailedForecast")))

(defn feature->alert
  [feature]
  (let [properties (get feature "properties")]
    (str "Event: " (get properties "event" "Unknown") "\n"
         "Area: " (get properties "areaDesc" "Unknown")  "\n"
         "Severity: " (get properties "severity" "Unknown")  "\n"
         "Description: " (get properties "description" "Unknown")  "\n"
         "Instructions: " (get properties "instruction" "Unknown")  "\n")))

(defn get-alerts
  [state-str]
  (let [url (str NWS_API_BASE "/alerts/active/area/" state-str)
        resp (get-nws url)
        features (-> (:body resp)
                     (json/read-str)
                     (get "features"))]
    (map feature->alert features)))

(defn get-forecast
  [lat lon]
  (let [url (str NWS_API_BASE "/points/" lat "," lon)
        forecast-url (-> (get-nws url)
                         (:body)
                         (json/read-str)
                         (get-in ["properties" "forecast"]))
        forecast-periods (-> (client/get forecast-url {:headers nws-headers})
                             (:body)
                             (json/read-str)
                             (get-in ["properties" "periods"]))]
    (map forecast->str (take 5 forecast-periods))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MCP Tools
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def tools
  [(mcp/tool {:name "weather_alert"
              :description "Given a two letter state code, finds the weather alerts for that state."
              :schema {"type" "object"
                       "properties" {"state" {"type" "string"
                                              "description" "Two letter state code"}}
                       "required" ["state"]}
              :handler (fn [_w {:keys [state]}]
                         (let [alerts (get-alerts state)]
                           {:content [{:type "text"
                                       :text (str/join "\n" (vec alerts))}]
                            :isError false}))})

   (mcp/tool {:name "weather_forecast"
              :description "Get weather forecast for a location"
              :schema {"type" "object"
                       "properties" {"latitude" {"type" "number"
                                                 "description" "Latitude of the location"}
                                     "longitude" {"type" "number"
                                                  "description" "Longitude of the location"}}
                       "required" ["latitude" "longitude"]}
              :handler (fn [_w {:keys [latitude longitude]}]
                         (let [forecast (get-forecast latitude longitude)]
                           {:content [{:type "text"
                                       :text (str/join "\n" (vec forecast))}]
                            :isError false}))})])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server setup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sessions (atom {}))

(defn get-register []
  (concat tools
          (mcp/routes)
          parts.ring.route/register))

(def system
  (atom {:system/get-register #'get-register
         :mcp/sessions sessions
         :mcp/server-info {:name "Weather MCP Server" :version "1.0.0"}
         :mcp/capabilities mcp/default-capabilities
         :mcp/protocol-version mcp/protocol-version}))

(defn -main [& _args]
  (server/start! system {:port 3999})
  (println "Weather MCP server started on http://localhost:3999/mcp"))
