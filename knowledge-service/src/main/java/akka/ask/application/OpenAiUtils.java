package akka.ask.application;

import akka.ask.KeyUtils;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;

public class OpenAiUtils {

  // FIXME: make this a singleton?
  public static OpenAiChatModel chatModel() {
    return OpenAiChatModel.builder()
      .apiKey(KeyUtils.readOpenAiKey())
      .modelName(OpenAiChatModelName.GPT_4_O_MINI)
      .build();
  }

  public static OpenAiEmbeddingModel embeddingModel() {
    return OpenAiEmbeddingModel.builder()
      .apiKey(KeyUtils.readOpenAiKey())
      .modelName(OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_LARGE)
      .build();
  }

}
