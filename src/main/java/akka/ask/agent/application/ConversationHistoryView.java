
package akka.ask.agent.application;

import java.util.List;

import akka.ask.agent.domain.SessionEvent;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ComponentId("view_chat_log")
public class ConversationHistoryView extends View {

  private final static Logger logger = LoggerFactory.getLogger(ConversationHistoryView.class);

  public record ChatMessage(String sessionId, String message, String origin, long timestamp) {
  }

  public record ChatMessages(List<ChatMessage> messages) {
  }

  @Query("SELECT * AS messages FROM view_chat_log WHERE sessionId = :id")
  public QueryEffect<ChatMessages> getMessagesBySession(String id) {
    return queryResult();
  }

  @Consume.FromEventSourcedEntity(SessionEntity.class)
  public static class ChatMessageUpdater extends TableUpdater<ChatMessage> {

    public Effect<ChatMessage> onEvent(SessionEvent event) {
      logger.debug("Received session event: {}", event);
      return switch (event) {
        case SessionEvent.AiMessageAdded added -> aiMessage(added);
        case SessionEvent.UserMessageAdded added -> userMessage(added);
      };
    }

    private Effect<ChatMessage> aiMessage(SessionEvent.AiMessageAdded added) {
      return effects().updateRow(
          new ChatMessage(added.sessionId(), added.content(), "ai", added.timeStamp().toEpochMilli()));
    }

    private Effect<ChatMessage> userMessage(SessionEvent.UserMessageAdded added) {
      return effects().updateRow(
          new ChatMessage(added.sessionId(), added.content(), "user", added.timeStamp().toEpochMilli()));
    }
  }

}
