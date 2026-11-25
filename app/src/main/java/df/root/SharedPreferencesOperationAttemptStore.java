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
 *
 * <p>Android may mutate the process-local preference view before a checked
 * commit is known to have reached disk. Consequently a false or throwing commit
 * makes THIS store instance uncertain: every later operation fails closed
 * instead of consulting the cache. A newly constructed store re-reads durable
 * state. See {@link OperationAttemptStore} for the full contract.</p>
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
    /** Guarded by STORE_LOCK; see the uncertain-commit contract on the interface. */
    private boolean uncertain;

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
            requireCertain();
            boolean hasId;
            boolean hasMode;
            boolean hasBootCount;
            boolean hasUnconfirmed;
            boolean hasReason;
            try {
                // Presence, not a default value, decides whether a record exists.
                hasId = preferences.contains(KEY_OPERATION_ID);
                hasMode = preferences.contains(KEY_EXECUTION_MODE);
                hasBootCount = preferences.contains(KEY_BOOT_COUNT);
                hasUnconfirmed = preferences.contains(KEY_UNCONFIRMED);
                hasReason = preferences.contains(KEY_REASON);
                if (!hasId && !hasMode && !hasBootCount && !hasUnconfirmed && !hasReason) {
                    return null;
                }
                if (!hasId || !hasMode || !hasBootCount || !hasUnconfirmed) {
                    throw new Failure("attempt record is incomplete");
                }
                String id = preferences.getString(KEY_OPERATION_ID, null);
                String mode = preferences.getString(KEY_EXECUTION_MODE, null);
                long bootCount = preferences.getLong(KEY_BOOT_COUNT, 0L);
                boolean unconfirmed = preferences.getBoolean(KEY_UNCONFIRMED, true);
                // A missing reason is valid; a wrong value type is not.
                String reason = hasReason ? preferences.getString(KEY_REASON, null) : null;
                RunConfig.ExecutionMode parsedMode = parseMode(mode);
                if (id == null || id.isEmpty() || !RunConfigArtifact.isValidOperationId(id)
                        || parsedMode == null || bootCount < 0L) {
                    throw new Failure("attempt record is corrupt");
                }
                return new OperationAttempt(id, parsedMode, bootCount, unconfirmed, reason);
            } catch (RuntimeException e) {
                throw new Failure("attempt record is unreadable", e);
            }
        }
    }

    @Override
    public boolean publish(OperationAttempt attempt) throws Failure {
        Objects.requireNonNull(attempt, "attempt");
        synchronized (STORE_LOCK) {
            requireCertain();
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
            requireCertain();
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

    private void requireCertain() throws Failure {
        if (uncertain) {
            throw new Failure("attempt store is uncertain after a failed commit; "
                    + "only a store created after a process restart re-reads durable state");
        }
    }

    private boolean commit(SharedPreferences.Editor editor) throws Failure {
        boolean committed;
        try {
            // Checked commit: a false result or thrown exception never admits a run.
            committed = editor.commit();
        } catch (RuntimeException e) {
            // The in-memory view may already have changed; never trust it again.
            uncertain = true;
            throw new Failure("attempt store commit failed", e);
        } catch (Error e) {
            uncertain = true;
            throw e;
        }
        if (!committed) {
            uncertain = true;
        }
        return committed;
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
