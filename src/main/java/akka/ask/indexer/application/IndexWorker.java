package akka.ask.indexer.application;

import akka.Done;
import akka.ask.indexer.domain.WorkerState;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@ComponentId("index-worker")
public class IndexWorker extends Workflow<WorkerState> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final String workerId;
  private final ComponentClient componentClient;
  private final EmbeddingIndexer embeddingIndexer;

  private static final String PROCESSING_FILE_STEP = "processing-file";
  private static final String NOTIFY_COMPLETION = "notify-completion";
  private static final String FAILED_FILE_PROCESSING = "failed-file-processing";
  private static final String NOTIFY_FAILURE = "notify-failure";

  public record IndexRequest(String parentId, Path pathToFile) {}

  public IndexWorker(
    WorkflowContext context,
    EmbeddingIndexer embeddingIndexer,
    ComponentClient componentClient) {

    this.workerId = context.workflowId();
    this.componentClient = componentClient;
    this.embeddingIndexer = embeddingIndexer;
  }

  private WorkerState currentStateOrNew(IndexRequest req) {
    if (currentState() == null) return WorkerState.init(req.parentId(), req.pathToFile());
    else return currentState();
  }

  public Effect<Done> process(IndexRequest req) {

    logger.debug("Worker [{}]: received request to index file: [{}]", workerId, req.pathToFile());
    var state = currentStateOrNew(req);

    if (state.isIdle())
      return effects()
        .updateState(state.indexing(req.pathToFile()))
        .transitionTo(PROCESSING_FILE_STEP)
        .thenReply(Done.getInstance());
    else
      return effects().error("Worker [" + workerId + "]: already processing file [" + state.pathToFile() + "]");

  }

  @Override
  public WorkflowDef<WorkerState> definition() {
    return workflow()
      .addStep(processing(), maxRetries(3).failoverTo(FAILED_FILE_PROCESSING))
      .addStep(notifyCompletion())
      .addStep(failed())
      .addStep(notifyFailure());
  }

  private Step processing() {
    return step(PROCESSING_FILE_STEP)
      .asyncCall(() -> {
        var path = currentState().pathToFile();
        logger.debug("Worker [{}]: indexing file: [{}]", workerId, path);
        return embeddingIndexer.indexFile(path);
      })
      .andThen(Done.class, __ ->
        effects()
          .updateState(currentState().idle())
          .transitionTo(NOTIFY_COMPLETION));
  }

  private Step notifyCompletion() {
   return step(NOTIFY_COMPLETION)
       .asyncCall(() ->
         // let the parent workflow know that the file has been processed
         componentClient
           .forWorkflow(currentState().parentId())
           .method(RagIndexingWorkflow::markFileAsIndexed)
           .invokeAsync(currentState().pathToFile())
       )
       // pause and wait for the parent workflow to send a new file
       .andThen(Done.class, cmd -> effects().pause());
  }

  private Step failed() {
    // no real asyncCall in this step, but we need to set the Worker to idle
    // before we notify the parent workflow that it failed to process the file
    return step(FAILED_FILE_PROCESSING)
      .asyncCall(() ->
        CompletableFuture.completedFuture(Done.getInstance()))
      .andThen(Done.class, __ ->
        effects()
          .updateState(currentState().idle())
          .transitionTo(NOTIFY_FAILURE)
      );
  }

  private Step notifyFailure() {
    return step(NOTIFY_FAILURE)
      .asyncCall(() ->
        // let the parent workflow know that it failed to process the file
        componentClient
          .forWorkflow(currentState().parentId())
          .method(RagIndexingWorkflow::markFileAsFailed)
          .invokeAsync(currentState().pathToFile())
      )
      // pause and wait for the parent workflow to send a new file
      .andThen(Done.class, cmd -> effects().pause());
  }
}
