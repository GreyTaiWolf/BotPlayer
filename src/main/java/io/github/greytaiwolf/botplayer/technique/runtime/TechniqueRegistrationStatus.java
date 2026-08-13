package io.github.greytaiwolf.botplayer.technique.runtime;

/** Explicit registry result; an existing version is never silently replaced. */
public enum TechniqueRegistrationStatus {
    REGISTERED,
    ALREADY_REGISTERED,
    VERSION_CONFLICT,
    CAPACITY_EXCEEDED,
    /** Registration ingress is permanently closed during server shutdown. */
    RUNTIME_CLOSED
}
