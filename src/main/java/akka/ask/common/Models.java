package akka.ask.common;

import com.mongodb.client.MongoClient;
import dev.langchain4j.model.anthropic.AnthropicChatModelName;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.voyageai.VoyageAiEmbeddingModel;
import dev.langchain4j.model.voyageai.VoyageAiEmbeddingModelName;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;

import java.time.Duration;

public class Models {

  public static StreamingChatLanguageModel streamingChatModel() {
    return AnthropicStreamingChatModel.builder()
        .apiKey(KeyUtils.readAnthropicApiKey())
        .modelName(AnthropicChatModelName.CLAUDE_3_5_SONNET_20240620)
        .build();
  }

  public static VoyageAiEmbeddingModel embeddingModel() {
    return VoyageAiEmbeddingModel.builder()
        .apiKey(KeyUtils.readVoyageApiKey())
        .modelName(VoyageAiEmbeddingModelName.VOYAGE_3_LITE)
        .timeout(Duration.ofSeconds(60))
        .logRequests(true)
        .logResponses(true)
        .build();
  }
}
