// Modified 2026-11-24 (DFRoot GZIF): prevent direct critical-preference I/O from returning to UI/environment code. See NOTICE.
package df.root;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigurationWriteBoundaryTest {
    @Test
    public void uiAndEnvironmentNeverBypassTheConfigurationRepository() throws Exception {
        assertNoDirectPreferenceIo("MainActivity.java");
        assertNoDirectPreferenceIo("SettingsFragment.java");
        assertNoDirectPreferenceIo("AndroidRunEnvironment.java");
    }

    private void assertNoDirectPreferenceIo(String fileName) throws Exception {
        Path source = TestFixtures.repositoryRoot().resolve("app/src/main/java/df/root")
                .resolve(fileName);
        String text = new String(Files.readAllBytes(source), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(fileName + " reads preferences directly", text.contains("getSharedPreferences"));
        assertFalse(fileName + " edits preferences directly", text.contains(".edit()"));
        assertFalse(fileName + " commits preferences directly", text.contains(".commit()"));
        assertFalse(fileName + " imports SharedPreferences",
                text.contains("import android.content.SharedPreferences"));
    }
}
