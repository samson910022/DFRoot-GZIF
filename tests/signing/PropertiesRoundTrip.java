import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** Test-only reader: loads signing.properties exactly as Gradle does. */
public class PropertiesRoundTrip {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected a signing.properties path");
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
            properties.load(input);
        }

        Base64.Encoder encoder = Base64.getEncoder();
        for (String key : List.of("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")) {
            String value = properties.getProperty(key);
            if (value == null) {
                throw new AssertionError("Missing property: " + key);
            }
            // Base64 keeps control characters out of the test process's stdout.
            System.out.println(key + "=" + encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
