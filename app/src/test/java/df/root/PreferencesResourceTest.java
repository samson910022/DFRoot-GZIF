// Modified 2026-11-24 (DFRoot GZIF): verify preference resources load with safe defaults. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): verify manual-only soft-reboot copy and status preference. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.res.XmlResourceParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class PreferencesResourceTest {
    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    @Test
    public void preferencesExposeExpectedKeysAndSafeDefaults() throws Exception {
        XmlResourceParser parser = RuntimeEnvironment.getApplication()
                .getResources().getXml(R.xml.preferences);
        Map<String, String> defaultValues = new HashMap<>();
        Map<String, String> summaries = new HashMap<>();
        Map<String, String> enabled = new HashMap<>();
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String key = parser.getAttributeValue(ANDROID_NAMESPACE, "key");
                if (key != null) {
                    defaultValues.put(key,
                            parser.getAttributeValue(ANDROID_NAMESPACE, "defaultValue"));
                    summaries.put(key,
                            parser.getAttributeValue(ANDROID_NAMESPACE, "summary"));
                    enabled.put(key,
                            parser.getAttributeValue(ANDROID_NAMESPACE, "enabled"));
                }
            }
        } finally {
            parser.close();
        }

        Set<String> expectedKeys = new HashSet<>();
        expectedKeys.add("boot_start");
        expectedKeys.add("boot_status");
        expectedKeys.add("boot_disable");
        expectedKeys.add("configuration_status");
        expectedKeys.add("configuration_retry");
        expectedKeys.add("soft_reboot");
        expectedKeys.add("disable_modules");

        assertEquals(expectedKeys, defaultValues.keySet());
        assertEquals("false", defaultValues.get("soft_reboot"));
        assertEquals("false", defaultValues.get("disable_modules"));
        assertTrue(summaries.get("boot_start").contains("Unavailable"));
        assertTrue(summaries.get("soft_reboot").contains("Unavailable"));
        assertEquals("false", enabled.get("boot_start"));
        assertEquals("false", enabled.get("soft_reboot"));
    }
}
