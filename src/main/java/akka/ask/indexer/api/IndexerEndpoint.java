package akka.ask.indexer.api;

import akka.ask.indexer.application.RagIndexing;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.HttpResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api/index")
public class IndexerEndpoint {

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final RagIndexing ragIndexing;

  public IndexerEndpoint(RagIndexing ragIndexing) {
    this.ragIndexing = ragIndexing;
  }

  @Post("/start")
  public HttpResponse startIndexation() {
    // FIXME: move this to a workflow and avoid starting indexing when already busy
    // fire and forget
    ragIndexing.indexDocuments();
    return HttpResponses.created();
  }
}
