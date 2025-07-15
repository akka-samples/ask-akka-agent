package akka.ask;

import akka.ask.agent.application.Knowledge;
import akka.ask.common.KeyUtils;
import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.typesafe.config.Config;

@Setup
public class Bootstrap implements ServiceSetup {

  private Config config;


  public Bootstrap(Config config) {
    this.config = config;
    KeyUtils.checkKeys(config);
  }


  @Override
  public DependencyProvider createDependencyProvider() {
    MongoClient mongoClient = MongoClients.create(config.getString("mongodb.uri"));
    Knowledge knowledge = new Knowledge(mongoClient);

    return new DependencyProvider() {
      @Override
      public <T> T getDependency(Class<T> cls) {
        if (cls.equals(MongoClient.class)) {
          return (T) mongoClient;
        }
        if (cls.equals(Knowledge.class)) {
          return (T) knowledge;
        }

        return null;
      }
    };
  }
}
