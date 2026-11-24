// Modified 2026-11-25 (DFRoot GZIF): verify the read-only Settings.Global boot-count boundary. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class BootCountReaderTest {
    private ContentResolver resolver;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        resolver = context.getContentResolver();
        Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, 0);
    }

    @Test
    public void positiveGlobalBootCountIsReturned() {
        Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, 37);
        assertEquals(37L, new BootCountReader.SettingsGlobal(resolver).read().longValue());
    }

    @Test
    public void missingSettingFailsClosed() {
        assertEquals(0, Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0));
        assertTrue(Settings.Global.putString(resolver, Settings.Global.BOOT_COUNT, null));
        assertNull(new BootCountReader.SettingsGlobal(resolver).read());
    }

    @Test
    public void nonPositiveSettingFailsClosed() {
        Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, 0);
        assertNull(new BootCountReader.SettingsGlobal(resolver).read());
        Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, -5);
        assertNull(new BootCountReader.SettingsGlobal(resolver).read());
    }

    @Test
    public void nullResolverIsRejected() {
        assertThrows(NullPointerException.class, () -> new BootCountReader.SettingsGlobal(null));
    }

    @Test
    public void readerNeverWritesTheSystemBootCount() throws Exception {
        java.io.File source = TestFixtures.repositoryRoot()
                .resolve("app/src/main/java/df/root/BootCountReader.java").toFile();
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        String code = text.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
        assertTrue(code.contains("Settings.Global.getInt"));
        assertFalse(code.contains("Settings.Global.putInt"));
        assertFalse(code.contains("Settings.Global.putString"));
        assertTrue(code.contains("BOOT_COUNT"));
    }
}
