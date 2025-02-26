package akka.ask.application;

import akka.Done;
import akka.ask.KeyUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.DefaultDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RagIndexing {
  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final DocumentSplitter splitter;
  // metadata key used to store file name
  private final String srcKey = "src";

  public RagIndexing() {
    this.splitter = new DocumentByCharacterSplitter(500, 50, OpenAiUtils.buildTokenizer());
  }

  public CompletionStage<Done> indexDocuments() {

    var embeddingModel = OpenAiUtils.embeddingModel();

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

      // FIXME: path to docs must be configurable
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
        var fileName = segment.metadata().getString(srcKey);
        var response = embeddingModel.embed(segment);
        logger.debug("Segment embedded. Source file '{}'. Tokens usage: in {}, out {}",
          fileName,
          response.tokenUsage().inputTokenCount(),
          response.tokenUsage().outputTokenCount());
        embeddingStore.add(response.content(), segment);
      }
    }

    return CompletableFuture.completedFuture(Done.getInstance());
  }



  // helper method to load Markdown documents
  // TODO: this is probably already done in langchain4j
  private List<Document> loadMarkdownDocuments(String directory) {
    try (Stream<Path> paths = Files.walk(Paths.get(directory))) {

      return paths
        .filter(Files::isRegularFile)
        .filter(path -> path.toString().endsWith(".md"))
        .map(path -> {

          try (InputStream input = Files.newInputStream(path)) {
            // read file as input stream
            Document doc = new TextDocumentParser().parse(input);
            return new DefaultDocument(doc.text(), Metadata.metadata(srcKey, path.getFileName().toString()));
          } catch (BlankDocumentException e) {
            return null; // some documents are blank, we need to skip them
          } catch (Exception e) {
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
