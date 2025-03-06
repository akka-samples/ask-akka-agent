package akka.ask.agent.application;

import akka.Done;
import akka.ask.agent.domain.SessionEvent;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static akka.ask.agent.application.SessionEntity.MessageType.AI;
import static akka.ask.agent.application.SessionEntity.MessageType.USER;

@ComponentId("session-entity")
public class SessionEntity extends EventSourcedEntity<SessionEntity.State, SessionEvent> {

  private final String sessionId;

  public SessionEntity(EventSourcedEntityContext context) {
    this.sessionId = context.entityId();
  }

  enum MessageType {
    AI,
    USER
  }

  public record SessionMessage(String content, long tokensUsed) {
  }

  public record Message(String content, MessageType type) {

    public boolean isUser() {
      return type == USER;
    }

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

  // FIXME: it -looks- like we're not publishing an add user message for the
  // user's initial query to the LLM. We want to make sure we're doing that.
  public Effect<Done> addUserMessage(SessionMessage sessionMessage) {
    return effects()
        .persist(new SessionEvent.UserMessageAdded(sessionId, sessionMessage.content(), sessionMessage.tokensUsed(),
            Instant.now()))
        .thenReply(__ -> Done.getInstance());
  }

  public Effect<Done> addAiMessage(SessionMessage sessionMessage) {
    return effects()
        .persist(new SessionEvent.AiMessageAdded(sessionId, sessionMessage.content(), sessionMessage.tokensUsed(),
            Instant.now()))
        .thenReply(__ -> Done.getInstance());
  }

  public Effect<Messages> getHistory() {
    return effects().reply(new Messages(currentState().messages));
  }

  @Override
  public State emptyState() {
    return State.empty();
  }

  @Override
  public State applyEvent(SessionEvent event) {
    return switch (event) {
      case SessionEvent.AiMessageAdded msg -> currentState().add(new Message(msg.content(), AI));
      case SessionEvent.UserMessageAdded msg -> currentState().add(new Message(msg.content(), USER));
    };
  }

}
