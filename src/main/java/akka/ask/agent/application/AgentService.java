package akka.ask.agent.application;

import akka.NotUsed;
import akka.ask.agent.application.SessionEntity.SessionMessage;
import akka.ask.common.MongoDbUtils;
import akka.ask.common.OpenAiUtils;
import akka.javasdk.client.ComponentClient;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Source;
import com.mongodb.client.MongoClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * This services works an interface to the AI.
 * It returns the AI response as a stream and can be used to stream out a
 * response
 * in a GrpcEndpoint or as SSE in an HttpEndpoint.
 */
public class AgentService {

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final ComponentClient componentClient;
  private final EmbeddingStoreContentRetriever contentRetriever;

  @SystemMessage("You are a very enthusiastic Akka representative who loves to help people! " +
      "Given the following sections from the Akka SDK documentation, answer the question using only that information, outputted in markdown format. "
      +
      "If you are unsure and the text is not explicitly written in the documentation, say:" +
      "Sorry, I don't know how to help with that.")
  interface Assistant {
    TokenStream chat(String message);
  }

  public record StreamedResponse(String content, int tokens, boolean finished) {
    public static StreamedResponse partial(String content) {
      return new StreamedResponse(content, 0, false);
    }

    public static StreamedResponse lastMessage(String content, int tokens) {
      return new StreamedResponse(content, tokens, true);
    }
  }

  public AgentService(ComponentClient componentClient, MongoClient mongoClient) {
    this.componentClient = componentClient;

    this.contentRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(MongoDbUtils.embeddingStore(mongoClient))
        .embeddingModel(OpenAiUtils.embeddingModel())
        .maxResults(10)
        .minScore(0.8)
        .build();
  }

  private void addUserMessage(String sessionId, String content, long tokensUsed) {
    componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionEntity::addUserMessage).invokeAsync(
            new SessionMessage(content, tokensUsed));
  }

  private void addAiMessage(String sessionId, String content, long tokensUsed) {
    componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionEntity::addAiMessage).invokeAsync(
            new SessionMessage(content, tokensUsed));
  }

  private CompletionStage<List<ChatMessage>> fetchHistory(String sessionId) {
    return componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionEntity::getHistory).invokeAsync()
        .thenApply(messages -> messages.messages().stream().map(this::toChatMessage).toList());
  }

  private ChatMessage toChatMessage(SessionEntity.Message msg) {
    return switch (msg.type()) {
      case AI -> new AiMessage(msg.content());
      case USER -> new UserMessage(msg.content());
    };
  }

  private MessageWindowChatMemory createChatMemory(String sessionId, List<ChatMessage> messages) {

    // this storage it not really a store, but an interface to the SessionEntity
    // initial state is set at creation (async call to entity)
    // later we use the 'store' to update the entity
    var chatMemoryStore = new ChatMemoryStore() {

      // it's initially set to message history coming from the entity
      // this is temp cache that survives only a single request
      private List<ChatMessage> localCache = messages;

      public List<ChatMessage> getMessages(Object memoryId) {
        return localCache;
      }

      @Override
      public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        logger.info("Updating messages for session {}, total size {}", sessionId, messages.size());

        // update local cache for next iterations
        localCache = messages;

        // send message to entity, in a fire and forget fashion
        if (!messages.isEmpty()) {
          var last = messages.getLast();
          // FIXME: make sure we get the real token usage value from the result stream so
          // it's available for events and subsequently views and entity state
          long tokens = 0; // TODO: get this real value from the message

          if (last instanceof AiMessage aiMessage) {
            addAiMessage(sessionId, aiMessage.text(), tokens);
          } else if (last instanceof UserMessage uMessage) {
            // only supporting test message for now
            addUserMessage(sessionId, uMessage.singleText(), tokens);
          }
        }
      }

      @Override
      public void deleteMessages(Object memoryId) {
        // nothing to do here for the moment
      }
    };

    return MessageWindowChatMemory.builder()
        .maxMessages(2000)
        .chatMemoryStore(chatMemoryStore)
        .build();
  }

  private Assistant createAssistant(String sessionId, List<ChatMessage> messages) {
    return AiServices.builder(Assistant.class)
        .streamingChatLanguageModel(OpenAiUtils.streamingChatModel())
        .chatMemory(createChatMemory(sessionId, messages))
        .contentRetriever(contentRetriever)
        .build();
  }

  public Source<StreamedResponse, NotUsed> ask(String sessionId, String question) {

    var historyFut = fetchHistory(sessionId);

    var assistantFut = historyFut.thenApply(messages -> createAssistant(sessionId, messages));

    return Source
        .fromCompletionStage(assistantFut)
        // once we have the assistant, we run the query that is itself streamed back
        .flatMapConcat(assistant -> fromTokenStream(assistant.chat(question)));

  }

  /**
   * Converts a TokenStream to an Akka Source
   */
  private static Source<StreamedResponse, NotUsed> fromTokenStream(TokenStream tokenStream) {
    // Create a source backed by an actor
    Source<StreamedResponse, akka.actor.ActorRef> source = Source.actorRef(
        msg -> {
          if (msg instanceof StreamedResponse res && res.finished) {
            return Optional.of(akka.stream.CompletionStrategy.immediately());
          } else {
            return Optional.empty();
          }
        },
        err -> {
          if (err instanceof Throwable)
            return Optional.of((Throwable) err);
          else
            return Optional.empty();
        },
        100,
        OverflowStrategy.dropHead());
    ;

    return source.mapMaterializedValue(actorRef -> {
      // Set up a consumer that sends tokens to the actor
      Consumer<String> tokenConsumer = token -> {
        var res = StreamedResponse.partial(token);
        actorRef.tell(res, akka.actor.ActorRef.noSender());
      };

      // Process the token stream
      CompletableFuture.runAsync(() -> {
        try {

          // Process tokens until the stream is done
          tokenStream
              .onPartialResponse(tokenConsumer)
              .onCompleteResponse(res -> {
                var lastMessage = StreamedResponse.lastMessage(res.aiMessage().text(),
                    res.tokenUsage().totalTokenCount());
                actorRef.tell(lastMessage, akka.actor.ActorRef.noSender());
              })
              .onError(error -> {
                actorRef.tell(error, akka.actor.ActorRef.noSender());
              })
              .start();

        } catch (Exception e) {
          actorRef.tell(new akka.actor.Status.Failure(e),
              akka.actor.ActorRef.noSender());
        }
      });

      return NotUsed.getInstance();
    });
  }
}
