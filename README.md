# Ask Akka Agentic AI Example
A proof of concept illustrating how to build an Agentic RAG workflow with Akka.

# OpenAi and MongoDb Atlas

This sample requires OpenAI API Key and a MongoDb Atlas URI. 

## Mongo Atlas
The Mongo DB atlas URI you get from signing up/logging in to https://cloud.mongodb.com
Create an empty database and add a database user with a password. Make sure to allow access from your local IP address
to be able to run the sample locally.

The Mongo DB console should now help out by giving you a URI/connection
string to copy. Note that you need to insert the database user password into the generated URI.

## Open API
To get the Open API key, sign up/log in to find the key at https://platform.openai.com/api-keys

## Pass keys to the service

The key and uri needs to be passed in env variables:
`OPENAI_API_KEY` and `MONGODB_ATLAS_URI` respectively.

Alternatively, you can add the key and uri in a file located at `src/main/resources/.env.local`. 

```
# src/main/resources/.env.local
# note: langchain4j has a 'demo' openAi key for testing.
OPENAI_API_KEY=demo
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

