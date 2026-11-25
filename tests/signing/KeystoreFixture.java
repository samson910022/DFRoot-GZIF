import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.Map;

/** Test-only JKS fixture builder for arbitrary aliases/passwords and cert-only entries. */
public class KeystoreFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected source, destination, and key|cert mode");
        }
        Map<String, String> environment = environment();
        char[] inputPassword = required(environment, "FIXTURE_INPUT_PASSWORD").toCharArray();
        char[] storePassword = required(environment, "FIXTURE_STORE_PASSWORD").toCharArray();
        char[] keyPassword = required(environment, "FIXTURE_KEY_PASSWORD").toCharArray();
        String alias = required(environment, "FIXTURE_ALIAS");
        try {
            KeyStore source = KeyStore.getInstance("JKS");
            try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
                source.load(input, inputPassword);
            }
            Key key = source.getKey("source", inputPassword);
            Certificate[] chain = source.getCertificateChain("source");
            if (!(key instanceof PrivateKey) || chain == null || chain.length == 0) {
                throw new AssertionError("Source fixture has no private-key entry");
            }

            KeyStore destination = KeyStore.getInstance("JKS");
            destination.load(null, storePassword);
            if (args[2].equals("key")) {
                destination.setEntry(
                        alias,
                        new KeyStore.PrivateKeyEntry((PrivateKey) key, chain),
                        new KeyStore.PasswordProtection(keyPassword));
            } else if (args[2].equals("cert")) {
                destination.setCertificateEntry(alias, chain[0]);
            } else {
                throw new IllegalArgumentException("Mode must be key or cert");
            }
            try (OutputStream output = Files.newOutputStream(
                    Path.of(args[1]), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                destination.store(output, storePassword);
            }
        } finally {
            java.util.Arrays.fill(inputPassword, '\0');
            java.util.Arrays.fill(storePassword, '\0');
            java.util.Arrays.fill(keyPassword, '\0');
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Map<String, String> environment() throws Exception {
        Map<String, String> values = new HashMap<>();
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(Path.of("/proc/self/environ"))))
                .toString();
        for (String entry : decoded.split("\0")) {
            int separator = entry.indexOf('=');
            if (separator > 0) {
                values.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        return values;
    }
}
