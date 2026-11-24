import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;

/** Test-only verification of a generated JKS and its non-BMP alias/password. */
public class KeystoreRoundTrip {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected a keystore path");
        }
        String password = System.getenv("SIGN_PASS");
        String alias = System.getenv("SIGN_ALIAS");
        if (password == null || alias == null) {
            throw new IllegalArgumentException("SIGN_PASS and SIGN_ALIAS are required");
        }

        char[] characters = password.toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("JKS");
            try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
                store.load(input, characters);
            }
            if (!store.containsAlias(alias)) {
                throw new AssertionError("Expected alias was not stored");
            }
            Key key = store.getKey(alias, characters);
            if (!(key instanceof PrivateKey)) {
                throw new AssertionError("Stored entry is not a private key");
            }
            System.out.println("JKS alias/password round-trip OK");
        } finally {
            java.util.Arrays.fill(characters, '\0');
        }
    }
}
