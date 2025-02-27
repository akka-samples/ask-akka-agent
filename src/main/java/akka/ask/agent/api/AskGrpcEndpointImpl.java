package akka.ask.agent.api;

import akka.NotUsed;
import akka.ask.common.MongoDbUtils;
import akka.ask.common.OpenAiUtils;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.GrpcEndpoint;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Source;
import com.mongodb.client.MongoClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@GrpcEndpoint
public class AskGrpcEndpointImpl implements AskGrpcEndpoint {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  interface Assistant {
    TokenStream chat(String message);
  }

  private final EmbeddingStoreContentRetriever contentRetriever;
  private final Assistant assistant;

  public AskGrpcEndpointImpl(MongoClient mongoClient) {
    var chatMemory =
      MessageWindowChatMemory.builder()
        .maxMessages(500)
        .build();

    this.contentRetriever = EmbeddingStoreContentRetriever.builder()
      .embeddingStore(MongoDbUtils.embeddingStore(mongoClient))
      .embeddingModel(OpenAiUtils.embeddingModel())
      .maxResults(10)
      .minScore(0.8)
      .build();

    this.assistant = AiServices.builder(Assistant.class)
      .streamingChatLanguageModel(OpenAiUtils.streamingChatModel())
      .chatMemory(chatMemory)
      .contentRetriever(contentRetriever)
      .build();
  }

  @Override
  public Source<QueryResponse, NotUsed> ask(QueryRequest in) {

    TokenStream tokenStream = assistant.chat(in.getQuestion());

    var src = fromTokenStream(tokenStream);
    return src.map(s -> QueryResponse.newBuilder().setAnswer(s.content).build());
  }

  private record StreamedResponse(String content, int tokens, boolean finished){
    public static StreamedResponse partial(String content) {
      return new StreamedResponse(content, 0, false);
    }

    public static StreamedResponse lastMessage(String content, int tokens) {
      return new StreamedResponse(content, tokens, true);
    }
  }
  /**
   * Converts a TokenStream to an Akka Source
   */
  public static Source<StreamedResponse, NotUsed> fromTokenStream(TokenStream tokenStream) {
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
        if (err instanceof Throwable) return Optional.of((Throwable) err);
        else return Optional.empty();
      },
      100,
      OverflowStrategy.dropHead()
    );
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
            .onCompleteResponse( res -> {
              var lastMessage = StreamedResponse.lastMessage(res.aiMessage().text(), res.tokenUsage().totalTokenCount());
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
