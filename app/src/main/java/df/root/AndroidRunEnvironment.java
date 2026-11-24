// Modified 2026-11-24 (DFRoot GZIF): adapt read-only Android observations and fail closed on unreadable properties. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): build environments only from confirmed RunConfig snapshots. See NOTICE.
package df.root;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.lang.reflect.Method;

/** Read-only Android implementation of the narrow preflight environment boundary. */
final class AndroidRunEnvironment implements RunPreflight.Environment {
    private final Context context;
    private final String selectedPackage;
    private final SystemPropertyReader propertyReader;

    private AndroidRunEnvironment(Context context, String selectedPackage) {
        this(context, selectedPackage, AndroidRunEnvironment::readSystemPropertyReflectively);
    }

    AndroidRunEnvironment(Context context, String selectedPackage,
                          SystemPropertyReader propertyReader) {
        this.context = context;
        this.selectedPackage = selectedPackage;
        this.propertyReader = propertyReader;
    }

    interface SystemPropertyReader {
        String get(String key, String defaultValue);
    }

    static AndroidRunEnvironment fromConfirmedConfiguration(Context context) {
        return forConfig(context, ConfigurationRepository.get(context).getConfirmedSnapshot());
    }

    static AndroidRunEnvironment forConfig(Context context, RunConfig config) {
        return new AndroidRunEnvironment(context,
                config == null ? null : config.getManagerPackage());
    }

    static AndroidRunEnvironment forSelection(Context context, String selectedPackage) {
        return new AndroidRunEnvironment(context, selectedPackage);
    }

    @Override
    public String getSelectedManagerPackage() {
        return selectedPackage;
    }

    @Override
    public boolean isManagerInstalled(String packageName) {
        if (packageName == null) {
            return false;
        }
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean isOwnerUser() {
        return AndroidUserGate.isOwner();
    }

    @Override
    public String getNativeLibraryDir() {
        return context.getApplicationInfo().nativeLibraryDir;
    }

    @Override
    public String getLogTag() {
        String value = propertyReader.get("persist.log.tag", "");
        if (value == null) {
            throw new IllegalStateException("unable to read persist.log.tag");
        }
        return value;
    }

    @Override
    public FirmwareGate.Inputs getFirmware() {
        return new FirmwareGate.Inputs(
                Build.DEVICE,
                Build.MODEL,
                Build.ID,
                Build.VERSION.SDK_INT,
                Build.VERSION.SECURITY_PATCH,
                Build.FINGERPRINT,
                System.getProperty("os.version", "unknown"));
    }

    static String readSystemPropertyReflectively(String key, String defaultValue) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, key, defaultValue);
            if (value == null) {
                throw new IllegalStateException("SystemProperties.get returned null");
            }
            return value;
        } catch (ReflectiveOperationException | SecurityException | ClassCastException e) {
            // An unreadable log-buffer setting is unknown, not proven enabled.
            throw new IllegalStateException("unable to read " + key, e);
        }
    }
}
