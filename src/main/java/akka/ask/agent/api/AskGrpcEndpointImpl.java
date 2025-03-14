package akka.ask.agent.api;

import akka.NotUsed;
import akka.ask.agent.application.AgentService;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.GrpcEndpoint;
import akka.stream.javadsl.Source;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@GrpcEndpoint
public class AskGrpcEndpointImpl implements AskGrpcEndpoint {

  private final AgentService agentService;

  public AskGrpcEndpointImpl(AgentService agentService) {
    this.agentService = agentService;
  }
  /**
   * This method runs the search and returns a streamed result.
   * Each stream element is a single token (word) returned by the AI.
   */
  @Override
  public Source<QueryResponse, NotUsed> ask(QueryRequest in) {
    return agentService
      .ask(in.getUserId(), in.getSessionId(), in.getQuestion())
      .map(s -> QueryResponse.newBuilder().setAnswer(s.content()).build());
  }


}
