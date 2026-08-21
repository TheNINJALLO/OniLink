package dev.onistone.onilink.virtual;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ValidatedActionPayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualInventoryModelTest {
    @Test
    void supportsRequiredSizesAndRejectsDuplicateSlots() {
        VirtualItem item = new VirtualItem("minecraft:diamond", 1, 0, "Select", List.of("Safe button"),
                ActionType.SEND_MESSAGE, new ValidatedActionPayload(1, Map.of("message", "selected")));
        for (int size : List.of(9, 27, 54)) {
            assertEquals(size, new VirtualContainerDefinition("Menu", size, 1, 1,
                    List.of(new VirtualSlot(0, item, false)), Duration.ofMinutes(1)).size());
        }
        assertThrows(IllegalArgumentException.class, () -> new VirtualContainerDefinition("Menu", 27, 1, 1,
                List.of(new VirtualSlot(0, item, false), new VirtualSlot(0, item, false)), Duration.ofMinutes(1)));
    }

    @Test
    void stackIdsArePositiveAndNotReusedConsecutively() {
        VirtualStackIdAllocator allocator = new VirtualStackIdAllocator();
        int first = allocator.next();
        int second = allocator.next();
        assertNotEquals(first, second);
    }

    @Test
    void virtualHooksCannotRecursivelyInvokeVirtualActions() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualItem(
                "minecraft:stone", 1, 0, "", List.of(), ActionType.OPEN_VIRTUAL_INVENTORY,
                new ValidatedActionPayload(1, Map.of())));
    }
}
