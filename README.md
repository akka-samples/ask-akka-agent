# Ask Akka Agentic AI Example
A proof of concept illustrating how to build an Agentic RAG workflow with Akka.

# Needed API Keys

This sample requires Anthropic API Key, a MongoDb Atlas URI and a Voyage. 

## Anthropic
The Anthropic key is gotten from the anthropic console: https://console.anthropic.com/
Create a workspace and add an API key for it.

## Voyage
Log in/create an account at https://www.voyageai.com
Create an organization, and an API key to access it.

Note that the free version of Voyage has a very low limit (3 RPM or 10K TPM), indexing the documentation will be very
slow.

## Mongo Atlas
The Mongo DB atlas URI you get from signing up/logging in to https://cloud.mongodb.com 
Create an empty database and add a database user with a password. Make sure to allow access from your local IP address
to be able to run the sample locally.

The Mongo DB console should now help out by giving you a URI/connection 
string to copy. Note that you need to insert the database user password into the generated URI. 

The key and uri needs to be passed in env variables: 
`ANTHROPIC_API_KEY`, `MONGODB_ATLAS_URI` and `VOYAGE_API_KEY` respectively. 

Alternatively, you can add the key and uri in a file located at `src/main/resources/.env.local`. 

```
# src/main/resources/.env.local
ANTHROPIC_API_KEY=YOUR-API-KEY-HERE
MONGODB_ATLAS_URI=YOUR-CONNECTION-STRING-HERE
```
This file is excluded from git by default and is intended to facilitate local development only.
Make sure to never push this to git.

# Indexing documentation

To create the vectorized index, call: 

```shell
curl -XPOST localhost:9000/api/index/start 
```

# Query the AI

Use the Web UI to make calls.
http://localhost:9000/

Alternatively, call the API directly using curl.

```shell
curl localhost:9000/api/ask --header "Content-Type: application/json" -XPOST \
--data '{ "userId": "001", "sessionId": "foo", "question":"How many components exist in the Akka SDK?"}'
```
This will run a query and save the conversational history in a SessionEntity identified by 'foo'.
Results are streamed using SSE.

