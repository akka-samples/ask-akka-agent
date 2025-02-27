package akka.ask;

import akka.Done;
import akka.actor.CoordinatedShutdown;
import akka.ask.common.KeyUtils;
import akka.ask.indexer.application.RagIndexing;
import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.stream.Materializer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Setup
public class Bootstrap implements ServiceSetup {

  private final MongoClient mongoClient;

  public Bootstrap(Materializer materializer) {
    this.mongoClient = MongoClients.create(KeyUtils.readMongoDbUri());

    CoordinatedShutdown.get(materializer.system()).addTask(
      CoordinatedShutdown.PhaseServiceUnbind(),
      "close-client", ()-> {
        System.out.println("Closing mongo client");
        mongoClient.close();
        return CompletableFuture.completedFuture(Done.getInstance());
      }
    );

  }

  @Override
  public void onStartup() {
    if (!KeyUtils.hasValidKeys()) {
      System.err.println("No API keys found. When running locally, make sure you have a " + ".env.local file located" +
        " under " +
        "src/main/resources/ (see src/main/resources/.env.example). When running in " + "production, make sure you have OPENAI_API_KEY and MONGODB_ATLAS_URI defined as environment variable.");
    }
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {
      @Override
      public <T> T getDependency(Class<T> cls) {
        if (cls.equals(RagIndexing.class)) {
          return (T) new RagIndexing(mongoClient);
        }

        if (cls.equals(MongoClient.class)) {
          return (T) mongoClient;
        }
        return null;
      }
    };
  }
}
