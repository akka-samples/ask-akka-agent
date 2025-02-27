package akka.ask.agent.domain;

import java.util.HashMap;
import java.util.Map;

public record SessionState(int usedTokens, Map<String, String> answers, boolean waiting) {

  public static SessionState empty() {
    return new SessionState(0, new HashMap<>(), false);
  }


  public SessionState addAnswer(String answerId, String answer) {
    var newAnswers = new HashMap<>(answers);
    newAnswers.put(answerId, answer);
    return new SessionState(usedTokens, newAnswers, false);
  }

  public SessionState increaseTokenUsage(int tokenCount) {
    return new SessionState(usedTokens + tokenCount, answers, false);
  }

  public String getAnswer(String num) {
    return answers.getOrDefault(num, "");
  }

  public boolean hasAnswer(String num) {
    return answers.containsKey(num);
  }

  public SessionState waitingForAnswer() {
    return new SessionState(usedTokens, answers, true);
  }

  public SessionState asNotWaiting() {
    return new SessionState(usedTokens, answers, false);
  }
}
