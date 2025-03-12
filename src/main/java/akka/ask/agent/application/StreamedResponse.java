package akka.ask.agent.application;

public record StreamedResponse(String content, int tokens, boolean finished) {
  public static StreamedResponse partial(String content) {
    return new StreamedResponse(content, 0, false);
  }

  public static StreamedResponse lastMessage(String content, int tokens) {
    return new StreamedResponse(content, tokens, true);
  }

  public static StreamedResponse empty() {
    return new StreamedResponse("", 0, true);
  }
}
