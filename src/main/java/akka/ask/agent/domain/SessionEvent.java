package akka.ask.agent.domain;

import java.time.Instant;
import akka.javasdk.annotations.TypeName;

public sealed interface SessionEvent {
  @TypeName("ai-message-added")
  public record AiMessageAdded(String userId, String sessionId, String content, long tokensUsed, Instant timeStamp)
      implements SessionEvent {
  }

  @TypeName("user-message-added")
  public record UserMessageAdded(String userId, String sessionId, String content, long tokensUsed, Instant timeStamp)
      implements SessionEvent {
  }

}
