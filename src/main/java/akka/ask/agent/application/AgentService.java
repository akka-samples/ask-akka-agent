package akka.ask.agent.application;

import akka.Done;
import akka.NotUsed;
import akka.ask.agent.application.SessionEntity.SessionMessage;
import akka.ask.common.KeyUtils;
import akka.ask.common.MongoDbUtils;
import akka.javasdk.client.ComponentClient;
import akka.stream.javadsl.Source;
import com.mongodb.client.MongoClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * This services works an interface to the AI.
 * It returns the AI response as a stream and can be used to stream out a
 * response
 * in a GrpcEndpoint or as SSE in an HttpEndpoint.
 */
public class AgentService {

  private final static Logger logger = LoggerFactory.getLogger(AgentService.class);
  private final ComponentClient componentClient;
  private final OpenAIClientAsync aiClient;

  private final EmbeddingStore<TextSegment> embeddingStore;
  private final EmbeddingModel embeddingModel = EmbeddingModel.TEXT_EMBEDDING_3_SMALL;
  private final ChatModel chatModel = ChatModel.GPT_4O_MINI;


  private final String sysMessage = """
      You are a very enthusiastic Akka representative who loves to help people!
      Given the following sections from the Akka SDK documentation, answer the question using only that information, outputted in markdown format. 
      If you are unsure and the text is not explicitly written in the documentation, say:
      Sorry, I don't know how to help with that.
      """;


  public AgentService(ComponentClient componentClient, MongoClient mongoClient) {
    this.componentClient = componentClient;

    this.aiClient = OpenAIOkHttpClient.builder().apiKey(KeyUtils.readOpenAiKey()).build().async();

    this.embeddingStore = MongoDbUtils.embeddingStore(mongoClient);
  }

  private CompletionStage<Done> addUserMessage(String compositeEntityId,
                                               String userId,
                                               String sessionId,
                                               String content,
                                               long tokensUsed) {
    return componentClient
        .forEventSourcedEntity(compositeEntityId)
        .method(SessionEntity::addUserMessage)
        .invokeAsync(new SessionMessage(userId, sessionId, content, tokensUsed));
  }

  private CompletionStage<Done> addAiMessage(String compositeEntityId,
                                             String userId,
                                             String sessionId,
                                             String content,
                                             long tokensUsed) {
    return componentClient
        .forEventSourcedEntity(compositeEntityId)
        .method(SessionEntity::addAiMessage)
        .invokeAsync(new SessionMessage(userId, sessionId, content, tokensUsed));
  }

  private CompletionStage<List<SessionEntity.Message>> fetchHistory(String entityId) {
    return componentClient
        .forEventSourcedEntity(entityId)
        .method(SessionEntity::getHistory).invokeAsync()
        .thenApply(SessionEntity.Messages::messages);
  }

  private CompletionStage<Embedding> createEmbedding(String text) {
    EmbeddingCreateParams params = EmbeddingCreateParams.builder()
        .input(text)
        .model(embeddingModel)
        .build();

    return aiClient.embeddings().create(params)
        .thenApply(response -> response.data().getFirst().embedding().stream().map(Double::floatValue).toList())
        .thenApply(Embedding::from);
  }

  private CompletionStage<List<TextSegment>> findRelevantDocuments(String query) {
    return createEmbedding(query)
        .thenApply(queryEmbedding -> {
          var matches = embeddingStore.search(EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding).maxResults(10).minScore(0.7).build());
          return matches.matches().stream()
              .map(EmbeddingMatch::embedded)
              .collect(Collectors.toList());
        });
  }

  private String formatContext(List<TextSegment> documents) {
    if (documents.isEmpty()) {
      return "No relevant information found.";
    }

    StringBuilder contextBuilder = new StringBuilder();

    for (int i = 0; i < documents.size(); i++) {
      TextSegment doc = documents.get(i);
      contextBuilder.append("Document ").append(i + 1).append(":\n");
      contextBuilder.append(doc.text()).append("\n\n");
    }

    return contextBuilder.toString();
  }

  public Source<StreamedResponse, NotUsed> ask(String userId, String sessionId, String userQuestion) {
    var compositeEntityId = userId + ":" + sessionId;

    // TODO: make sure that user message is not persisted until LLM answer is added
    // maybe instead of sending question and answer apart, we should end it as one message to the entity.
    // either both are added to the history or none
    var history =
        addUserMessage(compositeEntityId, userId, sessionId, userQuestion, 0)
            .thenCompose(__ -> fetchHistory(compositeEntityId));

    var docContext =
        findRelevantDocuments(userQuestion)
            .thenApply(this::formatContext);

    CompletionStage<List<ResponseInputItem>> moreInput =
        history.thenCompose(messages -> {
          return docContext.thenApply(context -> {
            var result = new ArrayList<ResponseInputItem>();
            var systemPrompt = sysMessage + "\n\n" + context;
            result.add(ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .content(systemPrompt)
                    .role(EasyInputMessage.Role.SYSTEM)
                    .build()));
            logger.debug("System prompt " + systemPrompt);

            List<ResponseInputItem> inputs =
                messages.stream().map(msg -> {
                  if (msg.isAi()) {
                    logger.debug("History AI: " + msg.content());
                    return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().content(msg.content()).role(EasyInputMessage.Role.ASSISTANT).build());
                  } else {
                    logger.debug("History USER: " + msg.content());
                    return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().content(msg.content()).role(EasyInputMessage.Role.USER).build());
                  }
                }).toList();
            result.addAll(inputs);

            return result;
          });
        });

    var params =
        moreInput.thenApply(inputs ->
            ResponseCreateParams.builder()
                .inputOfResponse(inputs)
                .model(chatModel)
                .build()
        );


    return Source.fromSourceCompletionStage(params.thenApply(p ->
            Source
                .<StreamedResponse>queue(10000)
                .mapMaterializedValue(queue -> {
                  var done = aiClient.responses().createStreaming(p)
                      .subscribe(event -> {
                        event.outputTextDelta().ifPresent(textEvent -> queue.offer(StreamedResponse.partial(textEvent.delta())));
                      })
                      .onCompleteFuture();

                  done.whenComplete((unused, error) -> {
                    if (error != null) {
                      logger.error("Something went wrong!", error);
                      queue.fail(error);
                    } else {
                      queue.complete();
                    }
                  });

                  return NotUsed.getInstance();
                })
        ))
        .mapMaterializedValue(d -> NotUsed.getInstance());
  }

}
