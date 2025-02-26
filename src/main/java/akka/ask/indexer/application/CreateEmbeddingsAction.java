package akka.ask.indexer.application;

import akka.Done;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionStage;

// TODO: refactor this to Workflow
//  This action is extremely inefficient. Instead we need a workflow that will index the files individually.
//  Spawn as many futures and collect the results.
@ComponentId("create-embeddings-action")
public class CreateEmbeddingsAction extends TimedAction {


  private final Logger logger = LoggerFactory.getLogger(getClass());
  // used to create a unique scheduler to re-index the documents
  public static String id = "create-embeddings";
  // settings defining the indexing frequency
  public static String indexIntervalKey = "indexing.internal";


  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;
  private final Config config;
  private final RagIndexing ragIndexing;

  public CreateEmbeddingsAction(ComponentClient componentClient,
                                TimerScheduler timerScheduler,
                                Config config,
                                RagIndexing ragIndexing) {
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
