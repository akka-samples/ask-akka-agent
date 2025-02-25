package akka.ask.application;

import akka.Done;
import akka.ask.KeyUtils;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.typesafe.config.Config;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentLoader;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.source.FileSystemSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ComponentId("create-embeddings-action")
public class CreateEmbeddingsAction extends TimedAction {


  private final Logger logger = LoggerFactory.getLogger(getClass());
  // used to create a unique scheduler to re-index the documents
  public static String id = "create-embeddings";
  // settings defining the indexing frequency
  public static String indexIntervalKey = "indexing.internal";

  //
  // FIXME: maybe we want to make this configurable?
  private int segmentSize = 1000;

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;
  private final Config config;
  private final RagIndexing ragIndexing;

  public CreateEmbeddingsAction(ComponentClient componentClient,
                                TimerScheduler timerScheduler,
                                Config config, RagIndexing ragIndexing) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
    this.config = config;
    this.ragIndexing = ragIndexing;
  }


  public Effect create() {
    var indexRes = ragIndexing.indexDocuments();
    var rescheduled = indexRes.thenCompose(__ -> reschedule());
    return effects().asyncDone(rescheduled);
  }

  private CompletionStage<Done> reschedule() {
    var duration = config.getDuration(CreateEmbeddingsAction.indexIntervalKey);
    logger.info("Next indexing schedule for '{}'", duration);
    return timerScheduler.startSingleTimer(id, duration, componentClient.forTimedAction().method(CreateEmbeddingsAction::create).deferred());
  }




}
