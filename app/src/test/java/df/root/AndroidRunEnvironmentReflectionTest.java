// Modified 2026-11-24 (DFRoot GZIF): prove reflective system-property failures never default to enabled. See NOTICE.
package df.root;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AndroidRunEnvironmentReflectionTest {
    @Test
    public void unavailableAndroidSystemPropertiesReflectionFailsClosed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AndroidRunEnvironment.readSystemPropertyReflectively("persist.log.tag", ""));

        assertTrue(error.getMessage().contains("persist.log.tag"));
    }
}
