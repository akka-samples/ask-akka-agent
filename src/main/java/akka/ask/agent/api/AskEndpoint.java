package akka.ask.agent.api;

import akka.ask.agent.application.SessionWorkflow;
import akka.ask.agent.domain.RunQuery;
import akka.ask.common.MongoDbUtils;
import akka.ask.common.OpenAiUtils;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.pattern.Patterns;
import akka.stream.Materializer;
import com.mongodb.client.MongoClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api/ask")
public class AskEndpoint {

  public record Question(String sessionId, String txt) {
  }

  @SystemMessage("You are a very enthusiastic Akka representative who loves to help people! " +
    "Given the following sections from the Akka SDK documentation, text the text using only that information, outputted in markdown format. " +
    "If you are unsure and the text is not explicitly written in the documentation, say:" +
    "Sorry, I don't know how to help with that.")
  interface Assistant {
    Result<String> chat(String userInput);
  }

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final ComponentClient componentClient;
  private final Materializer materializer;
  private final MongoClient mongoClient;


  private final Assistant assistant;

  public AskEndpoint(ComponentClient componentClient, MongoClient mongoClient, Materializer materializer) {
    this.componentClient = componentClient;
    this.mongoClient = mongoClient;
    this.materializer = materializer;

    var chatMemory =
      MessageWindowChatMemory.builder()
        .maxMessages(10)
        .build();

    var contentRetriever = EmbeddingStoreContentRetriever.builder()
      .embeddingStore(MongoDbUtils.embeddingStore(mongoClient))
      .embeddingModel(OpenAiUtils.embeddingModel())
      .build();

    this.assistant =
      AiServices.builder(Assistant.class)
        .chatLanguageModel(OpenAiUtils.chatModel())
        .chatMemory(chatMemory)
        .contentRetriever(contentRetriever)
        .build();

  }

  @Post
  public String chat(Question q) {
    logger.info(" received input: {}", q);
    return this.assistant.chat(q.txt()).content();
  }

  @Post("/flow")
  public CompletionStage<String> askFlow(Question q) {

    // send text
    var answerIdFut = componentClient
      .forWorkflow(q.sessionId)
      .method(SessionWorkflow::ask)
      .invokeAsync(new RunQuery(q.txt()));

    // wait until response is available
    return answerIdFut.thenCompose(answerId -> fetchAnswer(q.sessionId, answerId));
  }

  private CompletionStage<String> fetchAnswer(String sessionId, String answerId) {

    Callable<CompletionStage<String>> attempt = () -> {
      return componentClient
        .forWorkflow(sessionId)
        .method(SessionWorkflow::fetchAnswer)
        .invokeAsync(answerId);
    };

    return Patterns.retry(
      attempt,
      10, // max attemps
      Duration.ofSeconds(1), // minBackoff
      Duration.ofSeconds(10), // maxBackoff
      0.2, // backoff factor
      materializer.system());
  }
}
