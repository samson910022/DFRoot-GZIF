// Modified 2026-11-25 (DFRoot GZIF): persist the operation-attempt barrier in an isolated DE store with checked commit. See NOTICE.
package df.root;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

/**
 * Device-protected-storage attempt barrier. The preferences file is dedicated
 * to admission attempts: it never shares a file or a key with configuration or
 * boot-claim state. Only a successful checked {@code commit()} changes the
 * durable barrier.
 */
final class SharedPreferencesOperationAttemptStore implements OperationAttemptStore {
    static final String PREFS_NAME = "dfroot_operation_attempt";
    static final String KEY_OPERATION_ID = "operation_id";
    static final String KEY_EXECUTION_MODE = "execution_mode";
    static final String KEY_BOOT_COUNT = "boot_count";
    static final String KEY_UNCONFIRMED = "unconfirmed";
    static final String KEY_REASON = "reason";

    /** Serializes read-modify-write across short-lived stores in this process. */
    private static final Object STORE_LOCK = new Object();

    private final SharedPreferences preferences;

    SharedPreferencesOperationAttemptStore(Context deviceProtectedContext) {
        this(Objects.requireNonNull(deviceProtectedContext, "deviceProtectedContext")
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    SharedPreferencesOperationAttemptStore(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    static SharedPreferencesOperationAttemptStore forDeviceProtectedStorage(Context context) {
        Context application = context.getApplicationContext();
        Context deContext = (application != null ? application : context)
                .createDeviceProtectedStorageContext();
        return new SharedPreferencesOperationAttemptStore(deContext);
    }

    @Override
    public OperationAttempt load() throws Failure {
        synchronized (STORE_LOCK) {
            String id;
            String mode;
            long bootCount;
            boolean unconfirmed;
            String reason;
            try {
                id = preferences.getString(KEY_OPERATION_ID, null);
                if (id == null || id.isEmpty()) {
                    return null;
                }
                mode = preferences.getString(KEY_EXECUTION_MODE, null);
                bootCount = preferences.getLong(KEY_BOOT_COUNT, 0L);
                unconfirmed = preferences.getBoolean(KEY_UNCONFIRMED, true);
                reason = preferences.getString(KEY_REASON, null);
            } catch (RuntimeException e) {
                throw new Failure("attempt record is unreadable", e);
            }
            RunConfig.ExecutionMode parsedMode = parseMode(mode);
            if (!RunConfigArtifact.isValidOperationId(id) || parsedMode == null || bootCount < 0L) {
                throw new Failure("attempt record is corrupt");
            }
            return new OperationAttempt(id, parsedMode, bootCount, unconfirmed, reason);
        }
    }

    @Override
    public boolean publish(OperationAttempt attempt) throws Failure {
        Objects.requireNonNull(attempt, "attempt");
        synchronized (STORE_LOCK) {
            OperationAttempt current = loadLocked();
            if (current != null && current.isUnconfirmed()
                    && !current.getOperationId().equals(attempt.getOperationId())) {
                return false;
            }
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(KEY_OPERATION_ID, attempt.getOperationId());
            editor.putString(KEY_EXECUTION_MODE, attempt.getMode().name());
            editor.putLong(KEY_BOOT_COUNT, attempt.getBootCount());
            editor.putBoolean(KEY_UNCONFIRMED, attempt.isUnconfirmed());
            if (attempt.getReason() == null) {
                editor.remove(KEY_REASON);
            } else {
                editor.putString(KEY_REASON, attempt.getReason());
            }
            return commit(editor);
        }
    }

    @Override
    public boolean clear(String operationId) throws Failure {
        if (operationId == null) {
            return false;
        }
        synchronized (STORE_LOCK) {
            OperationAttempt current = loadLocked();
            if (current == null || !current.getOperationId().equals(operationId)) {
                return false;
            }
            SharedPreferences.Editor editor = preferences.edit();
            editor.remove(KEY_OPERATION_ID);
            editor.remove(KEY_EXECUTION_MODE);
            editor.remove(KEY_BOOT_COUNT);
            editor.remove(KEY_UNCONFIRMED);
            editor.remove(KEY_REASON);
            return commit(editor);
        }
    }

    private OperationAttempt loadLocked() throws Failure {
        // load() takes STORE_LOCK; Java monitors are reentrant, so this is safe.
        return load();
    }

    private boolean commit(SharedPreferences.Editor editor) throws Failure {
        try {
            // Checked commit: a false result or thrown exception never admits a run.
            return editor.commit();
        } catch (RuntimeException e) {
            throw new Failure("attempt store commit failed", e);
        }
    }

    private static RunConfig.ExecutionMode parseMode(String value) {
        if (RunConfig.ExecutionMode.MANUAL.name().equals(value)) {
            return RunConfig.ExecutionMode.MANUAL;
        }
        if (RunConfig.ExecutionMode.UNATTENDED_BOOT.name().equals(value)) {
            return RunConfig.ExecutionMode.UNATTENDED_BOOT;
        }
        return null;
    }
}
