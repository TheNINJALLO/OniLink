package dev.onistone.onilink.virtual;

public record VirtualSlot(int index, VirtualItem item, boolean disabled) {
    public VirtualSlot {
        if (index < 0 || index >= 54) throw new IllegalArgumentException("virtual slot index must be 0..53");
        if (item == null) throw new IllegalArgumentException("virtual slot item is required");
    }
}
