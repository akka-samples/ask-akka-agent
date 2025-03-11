package akka.ask.agent.application;

import akka.Done;
import akka.NotUsed;
import akka.ask.agent.application.SessionEntity.SessionMessage;
import akka.ask.common.MongoDbUtils;
import akka.ask.common.OpenAiUtils;
import akka.javasdk.client.ComponentClient;
import akka.stream.javadsl.Source;
import com.mongodb.client.MongoClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * This services works an interface to the AI.
 * It returns the AI response as a stream and can be used to stream out a
 * response
 * in a GrpcEndpoint or as SSE in an HttpEndpoint.
 */
public class AgentService {

  private final static Logger logger = LoggerFactory.getLogger(AgentService.class);
  private final ComponentClient componentClient;
  private final EmbeddingStoreContentRetriever contentRetriever;

  private final String sysMessage = """
    You are a very enthusiastic Akka representative who loves to help people!
    Given the following sections from the Akka SDK documentation, answer the question using only that information, outputted in markdown format. 
    If you are unsure and the text is not explicitly written in the documentation, say:
    Sorry, I don't know how to help with that.
    """;

  interface Assistant {
    TokenStream chat(String message);
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

  private CompletionStage<Done> addUserMessage(String sessionId, String content, long tokensUsed) {
    return componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionEntity::addUserMessage)
      .invokeAsync(new SessionMessage(content, tokensUsed));
  }

  private CompletionStage<Done> addAiMessage(String sessionId, String content, long tokensUsed) {
    return componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionEntity::addAiMessage)
      .invokeAsync(new SessionMessage(content, tokensUsed));
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
    var chatMemoryStore = new ChatMemoryStore() {

      // it's initially set to message history coming from the entity
      // this is temp cache that survives only a single request
      private List<ChatMessage> localCache = messages;
      public List<ChatMessage> getMessages(Object memoryId) {
        return localCache;
      }

      @Override
      public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        logger.info("Updating messages for session '{}', total size {}", sessionId, messages.size());
        // update local cache for next iterations
        localCache = messages;
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

  private Assistant createAssistant(String sessionId, String userQuestion, List<ChatMessage> messages) {

    var chatLanguageModel = OpenAiUtils.streamingChatModel();

    RetrievalAugmentor retrievalAugmentor =
      DefaultRetrievalAugmentor.builder()
        .contentRetriever(contentRetriever)
        .build();

    return AiServices.builder(Assistant.class)
      .streamingChatLanguageModel(chatLanguageModel)
      .chatMemory(createChatMemory(sessionId, messages))
      .retrievalAugmentor(retrievalAugmentor)
      .systemMessageProvider(__ -> sysMessage)
      .build();
  }

  public Source<StreamedResponse, NotUsed> ask(String sessionId, String userQuestion) {

    // TODO: make sure that user message is not persisted until LLM answer is added
    // maybe instead of sending question and answer apart, we should end it as one message to the entity.
    // either both are added to the history or none
    var historyFut =
      addUserMessage(sessionId, userQuestion, 0)
        .thenCompose(__ -> fetchHistory(sessionId));

    var assistantFut = historyFut.thenApply(messages -> createAssistant(sessionId, userQuestion, messages));

    return Source
        .completionStage(assistantFut)
        // once we have the assistant, we run the query that is itself streamed back
      .flatMapConcat(assistant -> AkkaStreamUtils.toAkkaSource(assistant.chat(userQuestion)))
        .mapAsync(1, res -> {
          if (res.finished()) {// is the last message?
            logger.debug("finished message");
            return addAiMessage(sessionId, res.content(), res.tokens())
              // since the full content has already been streamed,
              // the last message can be transformed to an empty message
              .thenApply(__ -> StreamedResponse.empty());
          }
          else {
            logger.debug("partial message {}", res);
            // other messages are streamed out to the caller
            // (those are the tokens emitted by the llm)
            return CompletableFuture.completedFuture(res);
          }
        });

  }

}
