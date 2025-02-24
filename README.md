# Ask Akka Agentic AI Example
A proof of concept illustrating how to build an Agentic RAG workflow with Akka.

## Knowledge Service
The [knowledge service](./knowledge-service) exposes a vendor-neutral API for indexing documents, generating embeddings, and performing semantic similarity searches. Any time tokens are used during the processing of a request, that information will be returned in the response. Consumers of this service have no knowledge of which underlying vector database or vector index is being used. If appropriate, it might be good to expose a "batch process" (workflow?) that will purge and re-index the entire knowledge store.

The knowledge service will get its URL and credentials to the vector database through secrets supplied as environment variables.

## AI Agent Service
The [agent service](./agent-service) is the API through which the UI and other clients will access the "Ask Akka" functionality. Its main function is to accept a query like "what are the components available in Akka?" or "create an empty entity called `ShoppingCart`" and then respond with a stream of text snippets that comprise the response. The `AgentSession` entity will emit `ResponseChunkReceived` events every time new data is received from the LLM's response stream. A configuration or policy dictates how long a conversation and its history can remain in storage before being deleted.

The agent service gets its OpenAI (or other LLM) API key from a secret supplied as an environment variable.

We will likely have a route that requests an SSE outbound stream based on a given session ID. This lets the UI handle and render chunks received from the LLM in realtime. An alternative we want to consider and document reasoning for use/reject is a gRPC endpoint consumed via grpc-web. What are the tradeoffs and why did we pick what we picked?

This service contains all of the LLM functionality as well as all of the session/conversation state and management functionality.

## UI Service
The [UI service](./ui-service) is an Akka service with an HTTP endpoint. It contains a tiny web UI embedded as static assets in the service itself, which are then returned by the appropriate routes in the endpoint. This service presents a ChatGPT-like UI that has a list of conversations (sessions) in the navigation bar. Users must authenticate to this application so that the agent service can use the supplied JWT to obtain a unique userID. 

## Usage Metering and Quota
To help illustrate a common pattern for preventing "agent runaway", this example also includes code that can limit and report on token usage by user and by session. The `AgentSession` entity will keep track of the tokens used both for the indexing/embedding and for the calls to the underlying LLM. It will get quota limits per user and per session _from the session started event_. These values can be pulled from environment variables when processing the _command_, but the event processor/session management needs to be deterministic.

The indexing service reports back the number of tokens used to generate an embedding and to index a document (though indexing new documents doesn't affect user quotas). Current usage and quota is maintained inside the `AgentSession` entity's state.

Usage by user is captured in an eventually consistent view, `UsageByUserView`.

