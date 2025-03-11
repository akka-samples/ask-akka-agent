
package akka.ask.agent.application;

import akka.ask.agent.domain.SessionEvent;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@ComponentId("view_chat_log")
public class ConversationHistoryView extends View {

  private final static Logger logger = LoggerFactory.getLogger(ConversationHistoryView.class);

  public record ChatMessage(String message, String origin, long timestamp) {
  }

  public record ChatMessages(String sessionId, List<ChatMessage> messages) {
    public ChatMessages add(ChatMessage message) {
      messages.add(message);
      return this;
    }
  }

  @Query("SELECT * FROM view_chat_log WHERE sessionId = :id")
  public QueryEffect<ChatMessages> getMessagesBySession(String id) {
    return queryResult();
  }

  @Consume.FromEventSourcedEntity(SessionEntity.class)
  public static class ChatMessageUpdater extends TableUpdater<ChatMessages> {

    public Effect<ChatMessages> onEvent(SessionEvent event) {
      return switch (event) {
        case SessionEvent.AiMessageAdded added -> aiMessage(added);
        case SessionEvent.UserMessageAdded added -> userMessage(added);
      };
    }

    private Effect<ChatMessages> aiMessage(SessionEvent.AiMessageAdded added) {
      ChatMessage newMessage = new ChatMessage(added.content(), "ai", added.timeStamp().toEpochMilli());
      return effects().updateRow(rowStateOrNew().add(newMessage));
    }

    private Effect<ChatMessages> userMessage(SessionEvent.UserMessageAdded added) {
      ChatMessage newMessage = new ChatMessage(added.content(), "user", added.timeStamp().toEpochMilli());
      return effects().updateRow(rowStateOrNew().add(newMessage));
    }


    private ChatMessages rowStateOrNew() {
      return rowState() != null ? rowState() : newState();
    }

    private ChatMessages newState() {
      if (updateContext().eventSubject().isEmpty()) {
        throw new IllegalStateException("Event subject is empty");
      }
      return new ChatMessages(updateContext().eventSubject().get(), new ArrayList<>());
    }
  }

}
