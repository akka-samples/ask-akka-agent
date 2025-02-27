package akka.ask;

import akka.ask.common.KeyUtils;
import akka.ask.indexer.application.RagIndexing;
import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;

@Setup
public class Bootstrap implements ServiceSetup {


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
      public <T> T getDependency(Class<T> clz) {
        if (clz.equals(RagIndexing.class)) {
          return (T) new RagIndexing();
        }
        return null;
      }
    };
  }
}
