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
@HttpEndpoint("/api")
public class UsersEndpoint {


  private final ComponentClient componentClient;

  public UsersEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/users/{userId}/sessions/")
  public CompletionStage<ConversationHistoryView.ConversationHistory> getSession(String userId) {

    return componentClient.forView()
        .method(ConversationHistoryView::getMessagesBySession)
        .invokeAsync(userId);
  }

}
