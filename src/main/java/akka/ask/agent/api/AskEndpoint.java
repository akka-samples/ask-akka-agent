package akka.ask.agent.api;

import akka.Done;
import akka.ask.agent.application.SessionWorkflow;
import akka.ask.agent.domain.RunQuery;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api/ask")
public class AskEndpoint {

  public record Message(String txt){}

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final ComponentClient componentClient;


  public AskEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post
  public Done chat(Message input) {
    logger.info("received input: {}", input);

    componentClient
      .forWorkflow("abc")
      .method(SessionWorkflow::ask)
      .invokeAsync(new RunQuery(input.txt()));

    return Done.getInstance();
  }
}
