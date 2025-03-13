package akka.ask.agent.application;

import akka.Done;
import akka.ask.agent.domain.SessionEvent;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static akka.ask.agent.application.SessionEntity.MessageType.AI;
import static akka.ask.agent.application.SessionEntity.MessageType.USER;

@ComponentId("session-entity")
public class SessionEntity extends EventSourcedEntity<SessionEntity.State, SessionEvent> {

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final String sessionId;

  public SessionEntity(EventSourcedEntityContext context) {
    this.sessionId = context.entityId();
  }

  enum MessageType {
    AI,
    USER
  }

  public record SessionMessage(String userId, String sessionId, String content, long tokensUsed) {
  }

  public record Message(String content, MessageType type) {

    @JsonIgnore
    public boolean isUser() {
      return type == USER;
    }

    @JsonIgnore
    public boolean isAi() {
      return type == AI;
    }
  }

  public record State(List<Message> messages) {
    public static State empty() {
      return new State(
          new ArrayList<>());
    }

    public State add(Message content) {
      messages.add(content);
      return new State(messages);
    }
  }

  public record Messages(List<Message> messages) {
  }

  public Effect<Done> addUserMessage(SessionMessage sessionMessage) {
    return effects()
        .persist(new SessionEvent.UserMessageAdded(sessionMessage.userId, sessionMessage.sessionId, sessionMessage.content(),
          sessionMessage.tokensUsed(),
            Instant.now()))
        .thenReply(__ -> Done.getInstance());
  }

  public Effect<Done> addAiMessage(SessionMessage sessionMessage) {
    logger.debug("Received AI message {}", sessionMessage);
    return effects()
        .persist(new SessionEvent.AiMessageAdded(sessionMessage.userId, sessionMessage.sessionId, sessionMessage.content(),
          sessionMessage.tokensUsed(),
            Instant.now()))
        .thenReply(__ -> Done.getInstance());
  }

  public Effect<Messages> getHistory() {
    logger.debug("Getting history from {}", commandContext().entityId());
    return
      effects().reply(new Messages(currentState().messages));
  }

  @Override
  public State emptyState() {
    return State.empty();
  }

  @Override
  public State applyEvent(SessionEvent event) {
    logger.debug("Session event: {}", event);
    return switch (event) {
      case SessionEvent.AiMessageAdded msg -> currentState().add(new Message(msg.content(), AI));
      case SessionEvent.UserMessageAdded msg -> currentState().add(new Message(msg.content(), USER));
    };
  }

}
