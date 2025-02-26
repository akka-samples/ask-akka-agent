package akka.ask;

import akka.ask.application.CreateEmbeddingsAction;
import akka.ask.application.RagIndexing;
import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;

import java.time.Duration;

@Setup
public class Bootstrap implements ServiceSetup {

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public Bootstrap(ComponentClient componentClient, TimerScheduler timerScheduler) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  @Override
  public void onStartup() {
    if (KeyUtils.hasValidKeys()) {
      // FIXME: document indexing should be triggered from external call
      //  for example: when new documents are published.
      timerScheduler.startSingleTimer(
        CreateEmbeddingsAction.id,
        Duration.ofSeconds(1), // run index on bootstrapping
        componentClient.forTimedAction().method(CreateEmbeddingsAction::create).deferred()
      );
    } else {
      System.err.println("No API keys found. When running locally, make sure you have a " + ".env.local file located under " +
        "src/main/resources/ (see src/main/resources/.env.example). When running in " + "production, make sure you have OPENAI_API_KEY and MONGODB_ATLAS_URI defined as environment variable.");
      System.exit(1);
    }
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {
      @Override
      public <T> T getDependency(Class<T> cls) {
        if (cls.equals(RagIndexing.class)) {
          return (T) new RagIndexing();
        }
        return null;
      }
    };
  }

//  @Override
//  public Set<Class<?>> disabledComponents() {
//    return Set.of(CreateEmbeddingsAction.class);
//  }
}
