package akka.ask.indexer.api;

import akka.ask.indexer.application.RagIndexingWorkflow;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionStage;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api/index")
public class IndexerEndpoint {

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final ComponentClient componentClient;
  private final String workflowId = "rag-indexing";

  public IndexerEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/start")
  public CompletionStage<HttpResponse> startIndexation() {
    return componentClient.forWorkflow(workflowId)
      .method(RagIndexingWorkflow::start)
      .invokeAsync()
      .thenApply(__ -> HttpResponses.accepted());
  }

  @Post("/pause")
  public CompletionStage<HttpResponse> pause() {
    return componentClient.forWorkflow(workflowId)
      .method(RagIndexingWorkflow::pause)
      .invokeAsync()
      .thenApply(__ -> HttpResponses.accepted());
  }

  @Post("/resume")
  public CompletionStage<HttpResponse> resume() {
    return componentClient.forWorkflow(workflowId)
      .method(RagIndexingWorkflow::resume)
      .invokeAsync()
      .thenApply(__ -> HttpResponses.accepted());
  }

  @Post("/abort")
  public CompletionStage<HttpResponse> abort() {
    return componentClient.forWorkflow(workflowId)
      .method(RagIndexingWorkflow::abort)
      .invokeAsync()
      .thenApply(__ -> HttpResponses.accepted());
  }
}
