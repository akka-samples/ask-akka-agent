
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

  public record ConversationHistory(List<Session> sessions) {

  }

  public record Message(String message, String origin, long timestamp) {
  }

  public record Session(String userId, String sessionId, long creationDate, List<Message> messages) {
    public Session add(Message message) {
      messages.add(message);
      return this;
    }
  }

  @Query("SELECT collect(*) as sessions FROM view_chat_log WHERE userId = :id ORDER by creationDate DESC")
  public QueryEffect<ConversationHistory> getMessagesBySession(String id) {
    return queryResult();
  }

  @Consume.FromEventSourcedEntity(SessionEntity.class)
  public static class ChatMessageUpdater extends TableUpdater<Session> {

    public Effect<Session> onEvent(SessionEvent event) {
      return switch (event) {
        case SessionEvent.AiMessageAdded added -> aiMessage(added);
        case SessionEvent.UserMessageAdded added -> userMessage(added);
      };
    }

    private Effect<Session> aiMessage(SessionEvent.AiMessageAdded added) {
      Message newMessage = new Message(added.content(), "ai", added.timeStamp().toEpochMilli());
      return effects().updateRow(rowStateOrNew(added.userId()).add(newMessage));
    }

    private Effect<Session> userMessage(SessionEvent.UserMessageAdded added) {
      Message newMessage = new Message(added.content(), "user",
        added.timeStamp().toEpochMilli());
      return effects().updateRow(rowStateOrNew(added.userId()).add(newMessage));
    }


    private Session rowStateOrNew(String userId) {
      return rowState() != null ? rowState() : newState(userId);
    }

    private Session newState(String userId) {
      if (updateContext().eventSubject().isEmpty()) {
        throw new IllegalStateException("Event subject is empty");
      }
      return new Session(
        userId,
        updateContext().eventSubject().get(),
        Instant.now().toEpochMilli(),
        new ArrayList<>());
    }
  }

}
