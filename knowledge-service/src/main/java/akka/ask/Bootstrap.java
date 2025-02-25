package akka.ask;

import akka.ask.application.CreateEmbeddingsAction;
import akka.ask.application.RagIndexing;
import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;

import java.time.Duration;
import java.util.Set;

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

    // FIXME: retry until call is confirmed to be scheduled
    timerScheduler.startSingleTimer(
      CreateEmbeddingsAction.id,
      Duration.ofSeconds(1), // run index on bootstrapping
      componentClient.forTimedAction().method(CreateEmbeddingsAction::create).deferred()
    );
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
