package akka.ask.indexer.application;

import akka.Done;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * This workflow reads the files under src/main/resources/flat-doc/ and create the vector embeddings that are later
 * used to augment the LLM context.
 */
@ComponentId("rag-indexing-workflow")
public class RagIndexingWorkflow extends Workflow<RagIndexingWorkflow.State> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final ComponentClient componentClient;

  private static final String ALLOCATE_FILES = "allocate-files";

  private final String workflowId;

  public RagIndexingWorkflow(WorkflowContext context, ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.workflowId = context.workflowId();
  }


  record Allocation(String workerId, Path path) {
  }

  record Allocated(String workerId, Path path) {
    public static Allocated empty() {
      return new Allocated("", null);
    }

    public boolean isEmpty() {
      return path == null;
    }
  }

  enum Status {
    IDLE,
    RUNNING,
    PAUSED,
    ABORTING;
  }

  public record State(int initialSize,
                      List<Path> toProcess,
                      List<Path> failedFiles,
                      List<String> freeWorkers,
                      Map<String, String> allocations, Status status) {

    public static State empty() {
      return new State(0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>(), Status.IDLE);
    }

    public static State running(List<Path> toProcess, List<String> freeWorkers) {
      return new State(toProcess.size(), toProcess, new ArrayList<>(), freeWorkers, new HashMap<>(), Status.RUNNING);
    }

    public State resume() {
      return new State(initialSize, toProcess, failedFiles, freeWorkers, allocations, Status.RUNNING);
    }

    public State pause() {
      return new State(initialSize, toProcess, failedFiles, freeWorkers, allocations, Status.PAUSED);
    }


    public State abort() {
      return new State(initialSize, toProcess, failedFiles, freeWorkers, allocations, Status.ABORTING);
    }


    public boolean isPaused() {
      return status == Status.PAUSED;
    }

    public boolean isRunning() {
      return status == Status.RUNNING;
    }

    public boolean isAborting() {
      return status == Status.ABORTING;
    }


    private Optional<Path> nextDocument() {
      if (toProcess.isEmpty()) return Optional.empty();
      else return Optional.of(toProcess.getFirst());
    }

    private Optional<String> nextFreeWorker() {
      return freeWorkers.stream().findFirst();
    }

    private Optional<Allocation> nextAllocation() {
      var nextDoc = nextDocument();
      var freeWorker = nextFreeWorker();
      if (nextDoc.isPresent() && freeWorker.isPresent()) {
        return Optional.of(new Allocation(freeWorker.get(), nextDoc.get()));
      } else {
        return Optional.empty();
      }
    }

    private State confirmAllocation(Allocated allocated) {
      allocations.put(allocated.path.toString(), allocated.workerId);
      freeWorkers.remove(allocated.workerId);
      toProcess.remove(allocated.path);
      return this;
    }

    public boolean hasInFlight() {
      return !allocations.isEmpty();
    }

    /**
     * Remove the allocation for the given path.
     * This method is used when a worker completes a file and the allocation is removed.
     * The worker is placed back to the free workers list.
     */
    public State deallocate(Path path) {
      var workerId = allocations.get(path.toString());
      if (workerId != null) {
        allocations.remove(path.toString());
        freeWorkers.add(workerId);
      }
      return this;
    }

    /**
     * Mark file as failed and also remove the allocation.
     * Once marked as failed, the file won't be retried.
     */
    public State markFailed(Path path) {
      failedFiles.add(path);
      return deallocate(path);
    }
  }

  @Override
  public State emptyState() {
    return State.empty();
  }



  public Effect<Done> start() {
    if (currentState().isRunning()) {
      return effects().error("Workflow is currently processing documents");
    } else if (currentState().isAborting()) {
      return effects().error("Workflow is currently aborting. Please, wait until it finishes in order to restart it.");
    } else if (currentState().isPaused()) {
      return effects().error("Workflow is currently paused. Please, resume or abort the current workflow first.");
    } else {

      List<Path> documents;
      var documentsDirectoryPath = getClass().getClassLoader().getResource("flat-doc").getPath();

      try (Stream<Path> paths = Files.walk(Paths.get(documentsDirectoryPath))) {
        documents = paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".md"))
          .toList();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      List<String> workers = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        // the worker id is derived from the workflowId and a sequential number.
        var workerId =  workflowId + "-worker-" + i;
        workers.add(workerId);
      }

      return effects()
        .updateState(State.running(documents, workers))
        .transitionTo(ALLOCATE_FILES)
        .thenReply(Done.getInstance());
    }
  }

  public Effect<Done> markFileAsIndexed(Path path) {
    var newState = currentState().deallocate(path);
    logProgress("File ["+ path +"] completed.", newState);
    return handleWorkerCall(newState, path);
  }


  public Effect<Done> markFileAsFailed(Path path) {
    var newState = currentState().markFailed(path);
    logProgress("File to process file ["+ path +"].", newState);
    return handleWorkerCall(newState, path);
  }


  private Effect<Done> handleWorkerCall(State newState, Path path) {
    if (newState.isPaused()) {
      // we still register worker call when paused,
      // but we don't initiate any new work
      return effects()
        .updateState(newState)
        .pause() // remain paused and don't transition
        .thenReply(Done.getInstance());

    } else if (newState.isAborting()) {
      // keep going until all workers finish their work
      if (newState.hasInFlight()) {
        return effects()
          .updateState(newState)
          .pause() // remain paused and don't transition
          .thenReply(Done.getInstance());
      } else {
        return effects()
          .updateState(emptyState())
          .pause()
          .thenReply(Done.getInstance());
      }

    } else {
      return effects()
        .updateState(newState)
        .transitionTo(ALLOCATE_FILES)
        .thenReply(Done.getInstance());
    }
  }

  public Effect<Done> pause() {
    logProgress("Pausing indexing", currentState());
    return effects()
      .updateState(currentState().pause())
      .pause()
      .thenReply(Done.getInstance());
  }

  public Effect<Done> resume() {
    logProgress("Resume indexing", currentState());
    return effects()
      .updateState(currentState().resume())
      .transitionTo(ALLOCATE_FILES)
      .thenReply(Done.getInstance());
  }

  public Effect<Done> abort() {
    logProgress("Abort current workflow", currentState());
    // when aborting, we pause the workflow because we don't want it to schedule new work
    // but we want to keep the workflow in a paused state in other to collect the inflight work
    return effects()
      .updateState(currentState().abort())
      .pause()
      .thenReply(Done.getInstance());
  }

  private void logProgress(String prefix, State state) {

    int inFlight = state.allocations.size();
    int toProcessSize = state.toProcess.size();
    var processedFiles = state.initialSize - toProcessSize - inFlight;

    logger.debug("{}: in-flight workers [{}], failed files [{}]. Total progress [{}/{}]",
      prefix,
      inFlight,
      state.failedFiles.size(),
      processedFiles,
      state.initialSize);
  }

  @Override
  public WorkflowDef<State> definition() {
    return workflow().addStep(allocateStep());
  }

  private Step allocateStep() {
    return step(ALLOCATE_FILES)
      .asyncCall(() -> {
          var allocation = currentState().nextAllocation();

          if (allocation.isEmpty()) {
            // No files to allocate anymore. We return an 'empty' allocation
            // which pauses the workflow. This also means that the workflow is will pause.
            return CompletableFuture.completedFuture(Allocated.empty());

          } else {
            var workerId = allocation.get().workerId;
            var path = allocation.get().path;
            logger.debug("RagWorkflow [{}]: allocating file [{}] to worker [{}]", workflowId, path, workerId);

            return componentClient
              .forWorkflow(workerId)
              .method(IndexWorker::process)
              .invokeAsync(new IndexWorker.IndexRequest(workflowId, path))
              .thenApply(__ -> new Allocated(workerId, path));
          }
        }
      ).andThen(Allocated.class, alloc -> {
        if (alloc.isEmpty())
          return effects().updateState(currentState().pause()).pause();
        else {
          logger.debug("RagWorkflow [{}]: file [{}] allocated to worker [{}]", workflowId, alloc.path, alloc.workerId);
          var newState = currentState().confirmAllocation(alloc);
          return effects().updateState(newState).transitionTo(ALLOCATE_FILES);
        }
      });
  }

}
