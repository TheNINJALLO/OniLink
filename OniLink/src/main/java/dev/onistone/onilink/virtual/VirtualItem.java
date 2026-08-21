package dev.onistone.onilink.virtual;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ValidatedActionPayload;

import java.util.List;

public record VirtualItem(
        String identifier,
        int count,
        int damage,
        String displayName,
        List<String> lore,
        ActionType action,
        ValidatedActionPayload actionPayload
) {
    public VirtualItem {
        identifier = identifier == null ? "" : identifier.trim().toLowerCase(java.util.Locale.ROOT);
        if (!identifier.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("virtual item identifier is invalid");
        }
        if (count < 1 || count > 64 || damage < 0 || damage > 65_535) {
            throw new IllegalArgumentException("virtual item count or damage is outside safe bounds");
        }
        displayName = clean(displayName, 256);
        lore = List.copyOf(lore == null ? List.of() : lore);
        if (lore.size() > 16) throw new IllegalArgumentException("virtual item lore has too many lines");
        for (String line : lore) clean(line, 256);
        if ((action == null) != (actionPayload == null)) {
            throw new IllegalArgumentException("virtual item action and payload must be configured together");
        }
        if (action != null && action.executionPlane() == dev.onistone.onilink.control.ExecutionPlane.VIRTUALIZED) {
            throw new IllegalArgumentException("virtual inventory hooks cannot recursively invoke virtual actions");
        }
    }

    private static String clean(String value, int maximum) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > maximum || clean.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("virtual item text is too long or contains NUL");
        }
        return clean;
    }
}
