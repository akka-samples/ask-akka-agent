package akka.ask.agent.application;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.List;

public class ChatMemoryEntityAdapter implements ChatMemory {
  @Override
  public Object id() {
    return null;
  }

  @Override
  public void add(ChatMessage message) {

  }

  @Override
  public List<ChatMessage> messages() {
    return List.of();
  }

  @Override
  public void clear() {

  }
}
