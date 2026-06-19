package aiplay.behaviortreebuilder.startut99.condition;

import aiplay.instance.InstanceConfigHelper;
import behaviortree.BehaviorTreeContext;
import behaviortree.ConditionNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Returns true when the UT99 NeuralNet webservice endpoint responds with a JSON body. This is a pragmatic "server is up" check and avoids relying on process names.
 */
public class IsNeuralNetWebserviceUpCondition extends ConditionNode {

  public IsNeuralNetWebserviceUpCondition(String name) {
    super(name);
  }

  @Override
  protected boolean checkCondition(BehaviorTreeContext context) {
    String url = aiplay.runtime.config.NeuralNetEndpointResolver.resolve(InstanceConfigHelper.get(context));
    if (url == null || url.isBlank()) {
      return false;
    }

    try {
      String body = httpGet(url.trim());
      if (body == null) {
        return false;
      }
      String t = body.trim();
      // Basic sanity: GameState is a JSON object in our setup.
      return t.startsWith("{") && t.endsWith("}");
    } catch (Exception ignore) {
      return false;
    }
  }

  private static String httpGet(String urlStr) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(250);
    conn.setReadTimeout(500);

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
      return sb.toString();
    }
  }
}

