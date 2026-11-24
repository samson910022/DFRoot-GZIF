// Modified 2026-11-24 (DFRoot GZIF): centralize durable configuration keys and consent version. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): native no longer reads this XML; it reads the run-config artifact. See NOTICE.
package df.root;

/** Java-side durable configuration keys; native consumes only the published run-config artifact. */
final class ConfigurationKeys {
    static final String PREFS_NAME = "dfroot";
    static final String PREF_SU_MANAGER = "su_manager";
    static final String PREF_SOFT_REBOOT = "soft_reboot";
    static final String PREF_DISABLE_MODULES = "disable_modules";
    static final String PREF_SOFT_REBOOT_ACK_VERSION = "soft_reboot_ack_version";
    static final String PREF_GENERATION = "run_config_generation";

    static final int CURRENT_SOFT_REBOOT_ACK_VERSION = 1;

    private ConfigurationKeys() {}
}
