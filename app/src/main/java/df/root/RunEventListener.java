// Modified 2026-11-24 (DFRoot GZIF): carry child uncertainty events to operation observers. See NOTICE.
package df.root;

/** Observational events for a started child whose ultimate effect is not yet known. */
public interface RunEventListener {
    void onTimeout();
    void onInterrupted();
}
