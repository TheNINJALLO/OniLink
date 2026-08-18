package dev.onistone.onilink;

/**
 * @deprecated renamed to {@link OniLink}. Kept so an old launch script or service unit naming this
 *         class keeps working; it does nothing but forward. Remove once every deployment names
 *         {@code dev.onistone.onilink.OniLink} (the jar's manifest already does).
 */
@Deprecated(forRemoval = true)
public final class EndstoneProxy {
    private EndstoneProxy() {
    }

    public static void main(String[] args) throws Exception {
        OniLink.main(args);
    }
}
