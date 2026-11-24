// Modified 2026-11-24 (DFRoot GZIF): define the coordinator's injectable resource ownership boundary. See NOTICE.
package df.root;

/**
 * A caller-created resource bundle transferred to {@link RunCoordinator} only
 * when a submission is accepted. The coordinator closes it exactly once after
 * actual operation completion; rejected submissions never take ownership.
 */
public interface ResourceLease extends AutoCloseable {
    @Override
    void close();
}
