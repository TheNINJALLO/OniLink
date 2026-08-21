package dev.onistone.onilink.virtual;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

public record VirtualContainerDefinition(String title, int size, int page, int pageCount,
                                         List<VirtualSlot> slots, Duration timeout) {
    public VirtualContainerDefinition {
        title = title == null ? "OniLink menu" : title.strip();
        if (title.isBlank() || title.length() > 128) throw new IllegalArgumentException("virtual menu title is invalid");
        if (size != 9 && size != 27 && size != 54) throw new IllegalArgumentException("virtual menu size must be 9, 27, or 54");
        if (page < 1 || pageCount < page) throw new IllegalArgumentException("virtual menu pagination is invalid");
        slots = List.copyOf(slots == null ? List.of() : slots);
        HashSet<Integer> indexes = new HashSet<>();
        for (VirtualSlot slot : slots) {
            if (slot.index() >= size || !indexes.add(slot.index())) throw new IllegalArgumentException("virtual menu slot is duplicate or out of range");
        }
        timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("virtual menu timeout must be 1ms..30m");
        }
    }
}
