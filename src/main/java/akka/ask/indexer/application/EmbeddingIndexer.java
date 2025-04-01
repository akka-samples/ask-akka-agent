package akka.ask.indexer.application;

import akka.Done;
import akka.ask.common.OpenAiUtils;
import com.mongodb.client.MongoClient;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.DefaultDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class EmbeddingIndexer {

  private final OpenAiEmbeddingModel embeddingModel;
  private final MongoDbEmbeddingStore embeddingStore;
  private final DocumentSplitter splitter;

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final CompletionStage<Done> futDone = CompletableFuture.completedFuture(Done.getInstance());

  public EmbeddingIndexer(MongoClient mongoClient) {

    this.embeddingModel = OpenAiUtils.embeddingModel();
    this.embeddingStore =
      MongoDbEmbeddingStore.builder()
        .fromClient(mongoClient)
        .databaseName("akka-docs")
        .collectionName("embeddings")
        .indexName("default")
        .createIndex(true)
        .build();

    this.splitter = new DocumentByCharacterSplitter(500, 50, OpenAiUtils.buildTokenizer());
  }



  public CompletionStage<Done> indexFile(Path path) {
    logger.debug("Indexing file: [{}]",  path.getFileName());
    if (path.toString().isEmpty()) return futDone;
    else {
      try (InputStream input = Files.newInputStream(path)) {
        // read file as input stream
        Document doc = new TextDocumentParser().parse(input);
        var docWithMetadata = new DefaultDocument(doc.text(), Metadata.metadata("src", path.getFileName().toString()));

        var segments = splitter.split(docWithMetadata);
        logger.debug("Created {} segments for document {}", segments.size(), path.getFileName());

        return segments
          .stream()
          .reduce(
            futDone,
            (acc, seg) -> addSegment(seg),
            (stage1, stage2) -> futDone
          );

      } catch (BlankDocumentException e) {
        // some documents are blank, we need to skip them
        return futDone;
      } catch (Exception e) {
        logger.error("Error reading file: [{}] - [{}]", path, e.getMessage());
        return futDone;
      }
    }
  }

  private CompletionStage<Done> addSegment(TextSegment seg) {
    // metadata key used to store file name
    String srcKey = "src";
    var fileName = seg.metadata().getString(srcKey);
    return
      CompletableFuture.supplyAsync(() -> embeddingModel.embed(seg))
        .thenCompose(res ->
          CompletableFuture.supplyAsync(() -> {
            logger.debug("Segment embedded. Source file [{}]. Tokens usage: in [{}], out [{}]",
              fileName,
              res.tokenUsage().inputTokenCount(),
              res.tokenUsage().outputTokenCount());

            return embeddingStore.add(res.content(), seg);
          }))
        .thenApply(__ -> Done.getInstance());
  }
}
