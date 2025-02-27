package akka.ask.agent.application;

import akka.Done;
import akka.ask.agent.domain.RunQuery;
import akka.ask.agent.domain.SessionState;
import akka.ask.common.MongoDbUtils;
import akka.ask.common.OpenAiUtils;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.mongodb.client.MongoClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static akka.javasdk.workflow.Workflow.RecoverStrategy.failoverTo;

@ComponentId("chat-session")
public class SessionWorkflow extends Workflow<SessionState> {

  @SystemMessage("You are a very enthusiastic Akka representative who loves to help people! " +
    "Given the following sections from the Akka SDK documentation, answer the question using only that information, " +
    "outputted in markdown format. If you are unsure and the answer is not explicitly written in the documentation, " +
    "say:" +
    "Sorry, I don't know how to help with that.")
  interface Assistant {
    Result<String> chat(String userInput);
  }

  public record QueryRequest(String id, String text){}
  public record QueryResponse(String id, String text, int totalTokenUsage){}
  private final ComponentClient componentClient;
  private final Assistant assistant;

  private final String ASK_AI_STEP = "asking-ai-step";
  private final String DISCARD_ANSWER = "discard-step";

  private final Logger logger = LoggerFactory.getLogger(getClass());


  public SessionWorkflow(ComponentClient componentClient, MongoClient mongoClient) {
    this.componentClient = componentClient;

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


  public Effect<String> ask(RunQuery runQuery) {
    var messageId = UUID.randomUUID().toString();
    logger.info("received request: {}", runQuery.question());
    switch (currentState()) {
      case null -> {
          var state = SessionState.empty();
          var query = new QueryRequest(messageId, runQuery.question());

          return effects()
            .updateState(state)
            .transitionTo(ASK_AI_STEP, query)
            .thenReply(messageId);
      }
      case SessionState state when state.waiting() ->  {
        // we need to reject new queries while we waiting for responses
        return effects().error("Query rejected. Session is busy");
      }
      default -> {

        var query = new QueryRequest(messageId, runQuery.question());
        return effects()
          .transitionTo(ASK_AI_STEP, query)
          .thenReply(messageId);
      }
    }
  }

  public Effect<Done> processResponse(QueryResponse runQuery) {
    var state =
      currentState()
        .addAnswer(runQuery.id, runQuery.text)
        .increaseTokenUsage(runQuery.totalTokenUsage);

    return effects().updateState(state).pause().thenReply(Done.getInstance());
  }

  public Effect<String> fetchAnswer(String answerId) {
    if (currentState().hasAnswer(answerId))
      return effects().reply(currentState().getAnswer(answerId));
    else
      return effects().error("Answer not available yet!");
  }

  @Override
  public WorkflowDef<SessionState> definition() {

    var askStep =
      step(ASK_AI_STEP)
        .asyncCall(QueryRequest.class, this::callAssistant)
        .andThen(Done.class,
          answer -> effects()
            .updateState(currentState().waitingForAnswer())
            .pause()
        );

    // if an answer take too long, we discard it
    var discardAnswer =
      step(DISCARD_ANSWER)
        .asyncCall(() -> CompletableFuture.completedFuture(Done.getInstance()))
        .andThen(Done.class, __ -> effects().updateState(currentState().asNotWaiting()).pause());


    return workflow()
      .defaultStepTimeout(Duration.ofSeconds(20))
      .addStep(askStep, failoverTo(DISCARD_ANSWER));
  }

  private CompletionStage<Done> callAssistant(QueryRequest query) {
    logger.info("making AI query: {}", query.text);
    CompletableFuture
      .supplyAsync(() -> this.assistant.chat(query.text))
      .thenCompose(res -> {
        logger.debug("Token usage: {})", res.tokenUsage());
        var queryRes = new QueryResponse(query.id, res.content(), res.tokenUsage().totalTokenCount());

        // call to itself when future complets
        return componentClient
          .forWorkflow(commandContext().workflowId())
          .method(SessionWorkflow::processResponse)
          .invokeAsync(queryRes);
      });
    return CompletableFuture.completedFuture(Done.getInstance());
  }
}
