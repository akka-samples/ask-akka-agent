package akka.ask.agent.application;

import akka.Done;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;

import java.util.ArrayList;
import java.util.List;

import static akka.ask.agent.application.SessionEntity.MessageType.AI;
import static akka.ask.agent.application.SessionEntity.MessageType.USER;

@ComponentId("session-entity")
public class SessionEntity extends EventSourcedEntity<SessionEntity.State, SessionEntity.Event> {

  enum MessageType {
    AI,
    USER
  }

  public record Message(String content, MessageType type){

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

  sealed interface Event {}
  public record AiMessageAdded(String content) implements Event{}
  public record UserMessageAdded(String content) implements Event{}

  public record Messages(List<Message> messages) {}

  public Effect<Done> addUserMessage(String content) {
    return effects().persist(new UserMessageAdded(content)).thenReply(__ -> Done.getInstance());
  }

  public Effect<Done> addAiMessage(String content) {
    return effects().persist(new AiMessageAdded(content)).thenReply(__ -> Done.getInstance());
  }

  public Effect<Messages> getHistory() {
    return effects().reply(new Messages(currentState().messages));
  }

  @Override
  public State emptyState() {
    return State.empty();
  }

  @Override
  public State applyEvent(SessionEntity.Event event) {
    return switch (event) {
      case AiMessageAdded msg -> currentState().add(new Message(msg.content, AI));
      case UserMessageAdded msg -> currentState().add(new Message(msg.content, USER));
    };
  }

}
