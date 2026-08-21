package dev.onistone.onilink.control;

import java.util.List;

public record ActionValidationResult(boolean valid, List<String> errors, List<String> warnings) {
    public ActionValidationResult {
        errors = List.copyOf(errors == null ? List.of() : errors);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        valid = errors.isEmpty();
    }

    public static ActionValidationResult accepted() {
        return new ActionValidationResult(true, List.of(), List.of());
    }

    public static ActionValidationResult rejected(String reason) {
        return new ActionValidationResult(false, List.of(reason), List.of());
    }
}
