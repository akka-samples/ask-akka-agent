package akka.ask.agent.application;

import akka.ask.agent.domain.RunQuery;
import akka.ask.agent.domain.SessionState;
import akka.ask.common.MongoDbUtils;
import akka.ask.common.OpenAiUtils;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.mongodb.client.MongoClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ComponentId("chat-session")
public class SessionWorkflow extends Workflow<SessionState> {

  @SystemMessage("You are a very enthusiastic Akka representative who loves to help people! " +
    "Given the following sections from the Akka SDK documentation, answer the question using only that information, outputted in markdown format. " +
    "If you are unsure and the answer is not explicitly written in the documentation, say:" +
    "Sorry, I don't know how to help with that.")
  interface Assistant {
    Result<String> chat(String userInput);
  }

  public record IndexedQuery(long idx, String question){}
  private final ComponentClient componentClient;
  private final Assistant assistant;

  private final String ASK_AI_STEP = "asking-ai-step";
  private final String REGISTER_ANSWER_STEP = "register-answer-step";
  private final String WAITING_STEP = "waiting-step";

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


  public Effect<Long> ask(RunQuery runQuery) {
    if (currentState() == null) {

      var state = SessionState.newInstance();
      var nextMessageIdx = state.nextMessageIdx();
      var query = new IndexedQuery(nextMessageIdx, runQuery.question());

      return effects()
        .updateState(state)
        .transitionTo(ASK_AI_STEP, query)
        .thenReply(nextMessageIdx);

    } else {

      var nextMessageIdx = currentState().nextMessageIdx();
      var query = new IndexedQuery(nextMessageIdx, runQuery.question());
      return effects()
        .transitionTo(ASK_AI_STEP, query)
        .thenReply(nextMessageIdx);
    }
  }

  @Override
  public WorkflowDef<SessionState> definition() {

    var askStep =
      step(ASK_AI_STEP)
        .asyncCall(IndexedQuery.class,
          runQuery -> callAssistant(runQuery))
        .andThen(String.class,
          answer -> effects().updateState(currentState().increaseMessageIndex()).pause());

    return workflow().addStep(askStep);
  }

  private CompletionStage<String> callAssistant(IndexedQuery query) {
    var res = this.assistant.chat(query.question);
    logger.debug("Response: '{}' (token usage: {})", res.content(), res.tokenUsage());
    return CompletableFuture.completedFuture(res.content());
  }
}
