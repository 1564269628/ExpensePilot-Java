import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Standalone HTTP readiness probe for the Java 21 runtime image.
 * No shell, curl, wget or external network dependency is required.
 */
public final class ExpensePilotHealthProbe {
    private ExpensePilotHealthProbe() { }

    public static void main(String[] args) {
        HttpURLConnection connection = null;
        try {
            var url = URI.create("http://127.0.0.1:8080/actuator/health").toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(4_000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) {
                System.exit(1);
            }
            String response;
            try (var input = connection.getInputStream()) {
                response = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!response.contains("\"status\":\"UP\"")) {
                System.exit(1);
            }
        } catch (Exception ignored) {
            System.exit(1);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
