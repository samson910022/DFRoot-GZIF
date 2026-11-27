// Modified 2026-11-24 (DFRoot GZIF): verify generated Gradle pins drive the canonical manager model. See NOTICE.
package df.root;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SuManagerTest {

    @Test
    public void classicManagerHasTheDocumentedLoaderAndPin() {
        SuManager manager = SuManager.CLASSIC;

        assertEquals("me.weishu.kernelsu", manager.getPackageName());
        assertEquals("libksud.so", manager.getLoaderName());
        assertEquals("731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4",
                manager.getLoaderSha256());
    }

    @Test
    public void nextManagerHasTheDocumentedLoaderAndPin() {
        SuManager manager = SuManager.NEXT;

        assertEquals("com.rifsxd.ksunext", manager.getPackageName());
        assertEquals("libksudnext.so", manager.getLoaderName());
        assertEquals("7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414",
                manager.getLoaderSha256());
    }

    @Test
    public void generatedBuildConfigPinsDriveTheManagerModel() {
        assertEquals("731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4",
                BuildConfig.BUNDLED_LOADER_CLASSIC_SHA256);
        assertEquals("7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414",
                BuildConfig.BUNDLED_LOADER_NEXT_SHA256);
        assertEquals(BuildConfig.BUNDLED_LOADER_CLASSIC_SHA256,
                SuManager.CLASSIC.getLoaderSha256());
        assertEquals(BuildConfig.BUNDLED_LOADER_NEXT_SHA256,
                SuManager.NEXT.getLoaderSha256());
    }

    @Test
    public void packageAllowlistIsDerivedFromTheModel() {
        assertArrayEquals(new String[] {
                "me.weishu.kernelsu",
                "com.rifsxd.ksunext"
        }, ExploitRunner.KSU_MANAGER_PACKAGES);
        assertEquals(2, SuManager.values().length);
    }

    @Test
    public void packageLookupIsExactAndNullSafe() {
        assertEquals(SuManager.CLASSIC, SuManager.fromPackage("me.weishu.kernelsu"));
        assertEquals(SuManager.NEXT, SuManager.fromPackage("com.rifsxd.ksunext"));
        assertNull(SuManager.fromPackage(null));
        assertNull(SuManager.fromPackage(""));
        assertNull(SuManager.fromPackage("ME.WEISHU.KERNELSU"));
        assertNull(SuManager.fromPackage("com.example.other"));
    }
}
