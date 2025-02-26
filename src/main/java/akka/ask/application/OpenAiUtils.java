package akka.ask.application;

import akka.ask.KeyUtils;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;
import dev.langchain4j.model.openai.OpenAiTokenizer;

public class OpenAiUtils {

  // using TEXT_EMBEDDING_3_SMALL because of 'demo' key
  final private static OpenAiEmbeddingModelName embeddingModelName = OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_SMALL;

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
      .modelName(embeddingModelName)
      .build();
  }

  public static Tokenizer buildTokenizer() {

    return new OpenAiTokenizer(embeddingModelName);
  }
}
