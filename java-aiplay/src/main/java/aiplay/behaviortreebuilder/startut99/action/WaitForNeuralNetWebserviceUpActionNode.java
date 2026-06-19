package aiplay.behaviortreebuilder.startut99.action;

import aiplay.instance.InstanceConfig;
import aiplay.instance.InstanceConfigHelper;
import behaviortree.ActionNode;
import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeStatus;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * After starting the dedicated server, the HTTP endpoint can take a moment to come up. If we start the client too early, UT may fail to connect and remain in the menu.
 * <p>
 * This node blocks briefly until the NeuralNet endpoint returns a JSON object.
 */
public class WaitForNeuralNetWebserviceUpActionNode extends ActionNode {

  private final long maxWaitMs;

  public WaitForNeuralNetWebserviceUpActionNode(String name, long maxWaitMs) {
    super(name);
    this.maxWaitMs = Math.max(250L, maxWaitMs);
  }

  @Override
  protected BehaviorTreeStatus execute(BehaviorTreeContext context) {
    InstanceConfig ic = InstanceConfigHelper.get(context);
    String url = aiplay.runtime.config.NeuralNetEndpointResolver.resolve(ic);
    if (url == null || url.isBlank()) {
      return BehaviorTreeStatus.FAILURE;
    }

    long deadline = System.currentTimeMillis() + maxWaitMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        String body = httpGet(url.trim());
        if (body != null) {
          String t = body.trim();
          if (t.startsWith("{") && t.endsWith("}")) {
            return BehaviorTreeStatus.SUCCESS;
          }
        }
      } catch (Exception ignore) {
      }

      try {
        Thread.sleep(200L);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    System.err.println("⚠️ NeuralNet webservice still not up after " + maxWaitMs + "ms: " + url);
    return BehaviorTreeStatus.FAILURE;
  }

  private static String httpGet(String urlStr) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(350);
    conn.setReadTimeout(650);

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

