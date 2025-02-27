package akka.ask.agent.api;

import akka.ask.agent.application.AgentService;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.stream.Materializer;

import java.util.concurrent.CompletionStage;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api/ask")
public class AskHttpEndpoint {


  public record QueryRequest(String sessionId, String question) {
  }

  private final AgentService agentService;
  private final Materializer materializer;

  public AskHttpEndpoint(AgentService agentService, Materializer materializer) {
    this.agentService = agentService;
    this.materializer = materializer;
  }

  /**
   * This method runs the search and concatenates the streamed result.
   */
  @Post
  public CompletionStage<String> ask(QueryRequest request) {
    return agentService
      .ask(request.sessionId, request.question)
       // concatenate all response tokens
      .runFold(new StringBuffer(), (acc, elem) -> acc.append(elem.content()), materializer)
      .thenApply(StringBuffer::toString);

  }
}
