
package akka.ask.agent.application;

import akka.ask.agent.domain.SessionEvent;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ComponentId("view_chat_log")
public class ConversationHistoryView extends View {

  private final static Logger logger = LoggerFactory.getLogger(ConversationHistoryView.class);

  public record ConversationHistory(List<SessionMessages> sessionMessages) {

  }

  public record SessionMessage(String message, String origin, long timestamp) {
  }

  public record SessionMessages(String userId, String sessionId, long creationDate, List<SessionMessage> messages) {
    public SessionMessages add(SessionMessage message) {
      messages.add(message);
      return this;
    }
  }

  @Query("SELECT collect(*) as sessionMessages FROM view_chat_log WHERE userId = :id ORDER by creationDate DESC")
  public QueryEffect<ConversationHistory> getMessagesBySession(String id) {
    return queryResult();
  }

  @Consume.FromEventSourcedEntity(SessionEntity.class)
  public static class ChatMessageUpdater extends TableUpdater<SessionMessages> {

    public Effect<SessionMessages> onEvent(SessionEvent event) {
      return switch (event) {
        case SessionEvent.AiMessageAdded added -> aiMessage(added);
        case SessionEvent.UserMessageAdded added -> userMessage(added);
      };
    }

    private Effect<SessionMessages> aiMessage(SessionEvent.AiMessageAdded added) {
      SessionMessage newMessage = new SessionMessage(added.content(), "ai", added.timeStamp().toEpochMilli());
      return effects().updateRow(rowStateOrNew(added.userId()).add(newMessage));
    }

    private Effect<SessionMessages> userMessage(SessionEvent.UserMessageAdded added) {
      SessionMessage newMessage = new SessionMessage(added.content(), "user",
        added.timeStamp().toEpochMilli());
      return effects().updateRow(rowStateOrNew(added.userId()).add(newMessage));
    }


    private SessionMessages rowStateOrNew(String userId) {
      return rowState() != null ? rowState() : newState(userId);
    }

    private SessionMessages newState(String userId) {
      if (updateContext().eventSubject().isEmpty()) {
        throw new IllegalStateException("Event subject is empty");
      }
      return new SessionMessages(
        userId,
        updateContext().eventSubject().get(),
        Instant.now().toEpochMilli(),
        new ArrayList<>());
    }
  }

}
