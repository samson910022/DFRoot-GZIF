// Modified 2026-11-25 (DFRoot GZIF): add the fail-closed BOOT_COUNT read seam for durable boot claims. See NOTICE.
package df.root;

import android.content.ContentResolver;
import android.provider.Settings;

import java.util.Objects;

/**
 * Reads the system boot counter. A null result means unavailable, not found, or
 * invalid; callers must fail closed. This boundary is read-only by construction.
 */
interface BootCountReader {
    Long read();

    /** Production reader over Settings.Global.BOOT_COUNT; never writes the setting. */
    final class SettingsGlobal implements BootCountReader {
        private final ContentResolver resolver;

        SettingsGlobal(ContentResolver resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        @Override
        public Long read() {
            try {
                int value = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT);
                return value > 0 ? Long.valueOf(value) : null;
            } catch (Settings.SettingNotFoundException e) {
                return null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
