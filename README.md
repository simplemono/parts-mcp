# Clojure MCP SDK

A composable Clojure SDK for building [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) servers, built on [parts.ring](https://github.com/simplemono/parts).

Tools, prompts, and resources are defined as plain data maps. The SDK provides Ring handlers that plug into any parts.ring system. You bring your own HTTP server and own the session state.

## Features

- **MCP Protocol**: Implements the MCP specification (protocol version 2025-06-18)
- **Streamable HTTP Transport**: HTTP/SSE via parts.ring + httpkit
- **Data-driven**: Tools, prompts, resources are registration entries (plain maps)
- **Composable**: Handlers integrate into any parts.ring system
- **Bring your own server**: httpkit included, but core handlers are server-agnostic

## Quick Start

Add to your `deps.edn`:

```clojure
{:deps {simplemono/mcp {:git/url "https://github.com/simplemono/mcp"
                        :git/sha "..."}}}
```

Create an MCP server:

```clojure
(ns my-mcp-server
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

;; Session state (user-provided atom)
(def sessions (atom {}))

;; Registration function - concatenates all entries
(defn get-register []
  (concat tools
          prompts
          (mcp/routes)
          parts.ring.route/register))

;; System / world map
(def system
  (atom {:system/get-register #'get-register
         :mcp/sessions sessions
         :mcp/server-info {:name "My MCP Server" :version "1.0.0"}
         :mcp/capabilities mcp/default-capabilities
         :mcp/protocol-version mcp/protocol-version}))

;; Start
(server/start! system {:port 3999})
```

## Registration Entries

Each helper returns a plain map that goes into the parts.ring register:

### `mcp/tool`

```clojure
(mcp/tool {:name "add"
           :description "Adds two numbers"
           :malli [:map [:a number?] [:b number?]]  ;; or :schema for raw JSON Schema
           :handler (fn [w args] {:content [{:type "text" :text (str (+ (:a args) (:b args)))}]})})
```

### `mcp/prompt`

```clojure
(mcp/prompt {:name "summarize"
             :description "Summarize text"
             :arguments [{:name "text" :description "Text to summarize" :required true}]
             :handler (fn [w args] [{:role "user" :content {:type "text" :text (:text args)}}])})
```

### `mcp/resource`

```clojure
(mcp/resource {:uri "info://readme"
               :name "README"
               :description "Project readme"
               :mimeType "text/plain"
               :handler (fn [w] {:contents [{:uri "info://readme" :text (slurp "README.md")}]})})
```

### `mcp/resource-template`

```clojure
(mcp/resource-template {:uriTemplate "file:///{path}"
                        :name "File"
                        :description "Read a file"
                        :handler (fn [w uri] {:contents [{:uri uri :text (slurp uri)}]})})
```

## Endpoints

`(mcp/routes)` returns three route entries:

| Method | Path | Description |
|--------|------|-------------|
| POST | `/mcp` | JSON-RPC requests, notifications, responses |
| GET | `/mcp` | SSE notification stream |
| DELETE | `/mcp` | Session termination |

Customize the path with `(mcp/routes {:path "/my-mcp"})`.

## Dynamic Notifications

When tools/prompts/resources change at runtime, notify connected clients:

```clojure
(mcp/notify-tools-changed! sessions)
(mcp/notify-prompts-changed! sessions)
(mcp/notify-resources-changed! sessions)
```

## Examples

See the `examples/` directory:

- `httpkit_server.clj` - Full example with tools, prompts, and resources
- `simple_mcp_server.clj` - Minimal server
- `weather_server.clj` - Weather tools using the NWS API

Run an example:

```sh
clj -M:example -m httpkit-server
```

## Acknowledgements

Based on the [Gaiwan MCP SDK](https://github.com/GaiwanTeam/mcp-sdk).

## License

Apache License, Version 2.0.
