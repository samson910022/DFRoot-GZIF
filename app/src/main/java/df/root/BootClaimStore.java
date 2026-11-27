// Modified 2026-11-25 (DFRoot GZIF): define the durable, checked boot-claim compare-and-set boundary. See NOTICE.
package df.root;

/** Durable boot-claim boundary. {@link #claim(long)} is a checked compare-and-set. */
interface BootClaimStore {
    /** Last durably claimed boot count, or a non-positive value when unset/unreadable. */
    long lastClaimed();

    /** Claims a strictly newer boot count. False means duplicate or failed persistence. */
    boolean claim(long bootCount);
}
