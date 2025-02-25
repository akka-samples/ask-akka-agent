package akka.ask.application;

import akka.Done;
import akka.ask.KeyUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RagIndexing {
  private final Logger logger = LoggerFactory.getLogger(getClass());

  // using TEXT_EMBEDDING_3_SMALL because of 'demo' key
  private final OpenAiEmbeddingModelName modelName = OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_SMALL;
  private final DocumentSplitter splitter;

  public RagIndexing() {
    var tokenizer = new OpenAiTokenizer(modelName);
    this.splitter = new DocumentByCharacterSplitter(500, 50, tokenizer);
  }

  public CompletionStage<Done> indexDocuments() {

    var embeddingModel =
      OpenAiEmbeddingModel.builder()
        .apiKey(KeyUtils.readOpenAiKey())
        .modelName(modelName)
        .build();

    // FIXME: terrible blocking code running inside action
    try (MongoClient mongoClient = MongoClients.create(KeyUtils.readMongoDbUri())) {

      var embeddingStore =
        MongoDbEmbeddingStore.builder()
          .fromClient(mongoClient)
          .databaseName("akka-docs")
          .collectionName("embeddings")
          .indexName("default")
          .createIndex(true)
          .build();

      var documentsDirectoryPath = getClass().getClassLoader().getResource("flat-doc").getPath();
      logger.debug("Loading documents from: '{}'", documentsDirectoryPath);

      // load documents
      List<Document> documents = loadMarkdownDocuments(documentsDirectoryPath);
      logger.debug("Loaded {} documents", documents.size());

      // create text segments and embed them
      List<TextSegment> textSegments =
        documents.stream()
          .flatMap(doc -> splitter.split(doc).stream())
          .toList();
      logger.debug("Created {} segments", textSegments.size());

      // embed and store segment
      for (TextSegment segment : textSegments) {
        var response = embeddingModel.embed(segment);
        logger.debug("Segment embedded, tokens usage: in {}, out {}",
          response.tokenUsage().inputTokenCount(),
          response.tokenUsage().outputTokenCount());
        embeddingStore.add(response.content(), segment);
      }
    }

    return CompletableFuture.completedFuture(Done.getInstance());
  }

  // helper method to load Markdown documents
  private List<Document> loadMarkdownDocuments(String directory) {
    try (Stream<Path> paths = Files.walk(Paths.get(directory))) {

      return paths
        .filter(Files::isRegularFile)
        .filter(path -> path.toString().endsWith(".md"))
        .map(path -> {

          try (InputStream input = Files.newInputStream(path)) {
            // read file as input stream
            return new TextDocumentParser().parse(input);
          } catch (BlankDocumentException e) {
            return null;
          } catch (IOException e) {
            logger.error("Error reading file: " + path + " - " + e.getMessage(), e);
            return null;
          }
        })
        .filter(doc -> doc != null)
        .collect(Collectors.toList());

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
