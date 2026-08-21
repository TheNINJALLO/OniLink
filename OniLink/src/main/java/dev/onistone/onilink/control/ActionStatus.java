package dev.onistone.onilink.control;

public enum ActionStatus {
    QUEUED,
    VALIDATING,
    SENT,
    ACCEPTED,
    EXECUTING,
    CONFIRMED,
    PARTIAL,
    REJECTED,
    UNSUPPORTED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
