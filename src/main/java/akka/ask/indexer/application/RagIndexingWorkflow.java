package akka.ask.indexer.application;

import akka.Done;
import akka.ask.common.Models;
import akka.ask.common.MongoDbUtils;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.workflow.Workflow;
import com.mongodb.client.MongoClient;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.DefaultDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.voyageai.VoyageAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

@ComponentId("rag-indexing-workflow")
public class RagIndexingWorkflow extends Workflow<RagIndexingWorkflow.State> {

  private static final Logger logger = LoggerFactory.getLogger(RagIndexingWorkflow.class);
  private final VoyageAiEmbeddingModel embeddingModel;
  private final EmbeddingStore<TextSegment> embeddingStore;
  private final DocumentSplitter splitter;
  // metadata key used to store file name
  private final String srcKey = "src";
  private static final String PROCESSING_FILE_STEP = "processing-file";


  private static final CompletionStage<Done> futDone = CompletableFuture.completedFuture(Done.getInstance());

  private final Timer timer = new Timer();

  public record State(List<Path> toProcess, List<Path> processed) {

    public static State of(List<Path> toProcess) {
      return new State(toProcess, new ArrayList<>());
    }

    public Optional<Path> head() {
      if (toProcess.isEmpty()) return Optional.empty();
      else return Optional.of(toProcess.getFirst());
    }

    public State headProcessed() {
      if (!toProcess.isEmpty()) {
        processed.add(toProcess.removeFirst());
      }
      return new State(toProcess, processed);
    }

    /**
     * @return true if workflow has one or more documents to process, false otherwise.
     */
    public boolean hasFilesToProcess() {
      return !toProcess.isEmpty();
    }

    public int totalFiles() {
      return processed.size() + toProcess.size();
    }
    public int totalProcessed() {
      return processed.size();
    }

  }

  @Override
  public State emptyState() {
    return State.of(new ArrayList<>());
  }


  public RagIndexingWorkflow(MongoClient mongoClient) {

    this.embeddingModel = Models.embeddingModel();
    this.embeddingStore = MongoDbUtils.embeddingStore(mongoClient);

    this.splitter = new DocumentByCharacterSplitter(500, 50);
  }

  public Effect<Done> start() {
    if (currentState().hasFilesToProcess()) {
      return effects().error("Workflow is currently processing documents");
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

      return effects()
        .updateState(State.of(documents))
        .transitionTo(PROCESSING_FILE_STEP)
        .thenReply(Done.getInstance());
    }
  }

  public Effect<Done> abort() {

    logger.debug("Aborting workflow. Current number of pending documents {}", currentState().toProcess.size());
    return effects()
      .updateState(emptyState())
      .pause()
      .thenReply(Done.getInstance());
  }

  @Override
  public WorkflowDef<State> definition() {

    var processing =
      step(PROCESSING_FILE_STEP)
        .asyncCall(() -> {
          if (currentState().hasFilesToProcess()) {
            return indexFile(currentState().head());
          }
          else
            return futDone;
        })
        .andThen(Done.class, __ -> {
            // we need to check if it hasFilesToProcess, before moving the head
            // because if workflow is aborted, the state is cleared, and we won't have anything in the list
            if (currentState().hasFilesToProcess()) {
              var newState = currentState().headProcessed();
              logger.debug("Processed {}/{}", newState.totalProcessed(), newState.totalFiles());
              return effects().updateState(newState).transitionTo(PROCESSING_FILE_STEP);
            } else {
              return effects().pause();
            }
          }
        );

    return workflow().addStep(processing);
  }


  private CompletionStage<Done> indexFile(Optional<Path> pathOpt) {

    if (pathOpt.isEmpty()) return futDone;
    else {

      var path = pathOpt.get();
      try (InputStream input = Files.newInputStream(path)) {
        // read file as input stream
        Document doc = new TextDocumentParser().parse(input);
        var docWithMetadata = new DefaultDocument(doc.text(), Metadata.metadata(srcKey, path.getFileName().toString()));

        var segments = splitter.split(docWithMetadata);
        logger.debug("Created {} segments for document {}", segments.size(), path.getFileName());

        return addSegments(segments);
      } catch (BlankDocumentException e) {
        // some documents are blank, we need to skip them
        return futDone;
      } catch (Exception e) {
        logger.error("Error reading file: {} - {}", path, e.getMessage());
        return futDone;
      }
    }
  }

  private CompletionStage<Done> addSegments(List<TextSegment> segments) {
    if (segments.isEmpty()) return futDone;
    else {
      var seg = segments.getFirst();
      var rest = segments.subList(1, segments.size());
      var fileName = seg.metadata().getString(srcKey);
      return
          CompletableFuture.supplyAsync(() -> embeddingModel.embed(seg))
              .thenCompose(res ->
                  CompletableFuture.supplyAsync(() -> {
                    logger.debug("Segment embedded. Source file '{}'. Dimension {}, Tokens usage: in {}, out {}",
                        fileName,
                        res.content().dimension(),
                        res.tokenUsage().inputTokenCount(),
                        res.tokenUsage().outputTokenCount());

                    return embeddingStore.add(res.content(), seg);
                  }))
              .thenCompose(__ -> {
                var delay = new CompletableFuture<Done>();
                logger.info("Delaying next indexing step to not hit Voyage rate limit");
                // Voyage claims free account allows at most 3RPM or 10K TPM, but it seems it blocks processing
                // way earlier than that, I have not been able to make it work however slow I make the processing
                timer.schedule(new TimerTask() {

              @Override
              public void run() {
                var restCompleted = addSegments(rest);
                restCompleted.whenComplete((done, error) -> {
                  if (error != null) {
                    delay.completeExceptionally(error);
                  } else {
                    delay.complete(done);
                  }
                });
              }
            }, 60000L);

            return delay;
          });
    }
  }
}
