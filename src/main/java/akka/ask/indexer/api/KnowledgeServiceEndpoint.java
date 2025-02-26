package akka.ask.indexer.api;

import akka.ask.common.OpenAiUtils;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api/ask/akka")
public class KnowledgeServiceEndpoint {

  private final ChatLanguageModel chatModel;

  private final Logger logger = LoggerFactory.getLogger(getClass());

  public record Message(String txt){}

  public KnowledgeServiceEndpoint() {
    this.chatModel = OpenAiUtils.chatModel();
  }

  @Post("/chat")
  public String chat(Message input) {
    logger.info("received input: {}", input);
    var res = chatModel.chat(input.txt);
    logger.info("answering output: {}", res);
    return res;
  }
}
