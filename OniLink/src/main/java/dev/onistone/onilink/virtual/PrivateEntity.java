package dev.onistone.onilink.virtual;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ControlRole;
import dev.onistone.onilink.control.ValidatedActionPayload;
import org.cloudburstmc.math.vector.Vector3f;

import java.time.Instant;

public record PrivateEntity(
        String id,
        long runtimeEntityId,
        String identifier,
        Vector3f position,
        Vector3f rotation,
        String name,
        float scale,
        Instant expiresAt,
        String actor,
        ControlRole actorRole,
        ActionType interactionAction,
        ValidatedActionPayload interactionPayload
) {
    public PrivateEntity {
        if (id == null || id.isBlank() || runtimeEntityId <= 0 || identifier == null || identifier.isBlank()
                || position == null || rotation == null || name == null || scale <= 0 || expiresAt == null
                || actor == null || actorRole == null) {
            throw new IllegalArgumentException("private entity identity, geometry, and expiry are required");
        }
    }
}
