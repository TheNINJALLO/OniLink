package dev.onistone.onilink.control;

import java.util.Locale;

public enum ControlRole {
    TENANT(-1), VIEWER(0), OPERATOR(1), ADMIN(2), OWNER(3);

    private final int rank;

    ControlRole(int rank) {
        this.rank = rank;
    }

    public boolean allows(ControlRole required) {
        return required != null && this != TENANT && rank >= required.rank;
    }

    public static ControlRole parse(String value) {
        if (value == null) throw new IllegalArgumentException("role is required");
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
