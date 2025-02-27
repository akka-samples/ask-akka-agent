package akka.ask.agent.domain;

public record SessionState(long messageIndex, long usedTokens) {

  public static SessionState newInstance() {
    return new SessionState(0l, 0l);
  }

  public long  nextMessageIdx() {
  return messageIndex + 1;
  }

  public SessionState increaseMessageIndex() {
    return new SessionState(this.nextMessageIdx(), usedTokens);
  }
}
