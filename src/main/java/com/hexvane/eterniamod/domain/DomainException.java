package com.hexvane.eterniamod.domain;

public final class DomainException extends RuntimeException {
    public enum Code { INVALID_INPUT, NOT_FOUND, CONFLICT, FORBIDDEN, INSUFFICIENT_BALANCE, INVALID_STATE, CAPACITY, STORAGE_UNAVAILABLE }
    private final Code code;
    public DomainException(Code code, String message) { super(message); this.code = code; }
    public DomainException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }
}
