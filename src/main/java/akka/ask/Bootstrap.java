package akka.ask;

import akka.Done;
import akka.actor.CoordinatedShutdown;
import akka.ask.agent.application.AgentService;
import akka.ask.common.KeyUtils;
import akka.ask.indexer.application.RagIndexing;
import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.stream.Materializer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@Setup
public class Bootstrap implements ServiceSetup {

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final MongoClient mongoClient;
  private final ComponentClient componentClient;

  public Bootstrap(
    ComponentClient componentClient,
    Materializer materializer) {

    if (!KeyUtils.hasValidKeys()) {
      System.err.println(
        "No API keys found. When running locally, make sure you have a " + ".env.local file located under " +
          "src/main/resources/ (see src/main/resources/.env.example). When running in production, " +
          "make sure you have OPENAI_API_KEY and MONGODB_ATLAS_URI defined as environment variable.");
      System.exit(1);
    }

    this.componentClient = componentClient;
    this.mongoClient = MongoClients.create(KeyUtils.readMongoDbUri());

    CoordinatedShutdown.get(materializer.system()).addTask(
      CoordinatedShutdown.PhaseServiceUnbind(),
      "close-client", ()-> {
        logger.debug("Closing mongo client");
        mongoClient.close();
        return CompletableFuture.completedFuture(Done.getInstance());
      }
    );

  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {
      @Override
      public <T> T getDependency(Class<T> cls) {
        if (cls.equals(RagIndexing.class)) {
          return (T) new RagIndexing(mongoClient);
        }

        if (cls.equals(AgentService.class)) {
          return (T) new AgentService(componentClient, mongoClient);
        }

        if (cls.equals(MongoClient.class)) {
          return (T) mongoClient;
        }
        return null;
      }
    };
  }
}
