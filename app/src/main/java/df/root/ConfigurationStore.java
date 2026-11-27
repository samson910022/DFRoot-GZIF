// Modified 2026-11-24 (DFRoot GZIF): define the narrow checked configuration persistence boundary. See NOTICE.
package df.root;

/** The complete durable configuration boundary; false means persistence failed. */
public interface ConfigurationStore {
    RunConfig load();
    boolean write(RunConfig config);
}
