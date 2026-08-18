package dev.onistone.onilink.backend;

public final class UnsupportedVersionPairException extends RuntimeException {
    public UnsupportedVersionPairException(String message) {
        super(message);
    }

    public UnsupportedVersionPairException(String message, Throwable cause) {
        super(message, cause);
    }
}
