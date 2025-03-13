package akka.ask.agent.api;

import akka.ask.agent.application.AgentService;
import akka.ask.agent.application.ConversationHistoryView;
import akka.ask.agent.application.StreamedResponse;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.Materializer;

import java.util.concurrent.CompletionStage;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api/ask")
public class AskHttpEndpoint {

  public record QueryRequest(String userId, String sessionId, String question) {
  }

  private final ComponentClient componentClient;
  private final AgentService agentService;
  private final Materializer materializer;

  public AskHttpEndpoint(AgentService agentService, Materializer materializer, ComponentClient componentClient) {
    this.agentService = agentService;
    this.materializer = materializer;
    this.componentClient = componentClient;
  }

  /**
   * This method runs the search and concatenates the streamed result.
   */
  @Post
  public HttpResponse ask(QueryRequest request) {

    var response = agentService
        .ask(request.userId, request.sessionId, request.question)
        .map(StreamedResponse::content);

    return HttpResponses.serverSentEvents(response);

  }

  @Get("/sessions/{userId}")
  public CompletionStage<ConversationHistoryView.ConversationHistory> getSession(String userId) {

    return componentClient.forView()
        .method(ConversationHistoryView::getMessagesBySession)
        .invokeAsync(userId);
  }

}
