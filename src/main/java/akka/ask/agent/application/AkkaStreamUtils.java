package akka.ask.agent.application;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import dev.langchain4j.service.TokenStream;


public class AkkaStreamUtils {

  /**
   * Converts a TokenStream to an Akka Source.
   * <p>
   * This method will build an Akka Source that is fed with the tokens produced by TokenStream.
   */
  public static Source<StreamedResponse, NotUsed> toAkkaSource(TokenStream tokenStream) {
    return
      Source
        .<StreamedResponse>queue(10000)
        .mapMaterializedValue(queue -> {
          // tokens emitted by tokenStream are passed to the queue
          // that ultimately feeds the Akka Source
          tokenStream
            .onPartialResponse(msg -> queue.offer(StreamedResponse.partial(msg)))
            .onCompleteResponse(res -> {
              queue.offer(StreamedResponse.lastMessage(res.aiMessage().text(), res.tokenUsage().totalTokenCount()));
              queue.complete();
            })
            .onError(queue::fail)
            .start();

          return NotUsed.getInstance();
        });

  }
}
