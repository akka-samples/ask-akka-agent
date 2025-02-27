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

  @Override
  public Source<QueryResponse, NotUsed> ask(QueryRequest in) {
    return agentService
      .ask(in.getSessionId(), in.getQuestion())
      .map(s -> QueryResponse.newBuilder().setAnswer(s.content()).build());
  }


}
