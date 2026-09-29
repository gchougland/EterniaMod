package com.hexvane.eterniamod.domain;

import java.util.Objects;
import java.util.UUID;

public record Owner(Kind kind, UUID id) {
    public enum Kind { PLAYER, GUILD, SERVER }
    public Owner { Objects.requireNonNull(kind); Objects.requireNonNull(id); }
    public static Owner player(UUID id) { return new Owner(Kind.PLAYER, id); }
    public static Owner guild(UUID id) { return new Owner(Kind.GUILD, id); }
    public String key() { return kind.name() + ":" + id; }
    public static Owner parse(String value) {
        int separator = value.indexOf(':');
        if (separator < 0) throw new DomainException(DomainException.Code.INVALID_INPUT, "Invalid owner");
        return new Owner(Kind.valueOf(value.substring(0, separator)), UUID.fromString(value.substring(separator + 1)));
    }
}
