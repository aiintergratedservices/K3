package com.hackerai.supervisor.model.value;

/** Context-map key marker: the natural-language user request. Referenced as UserRequest.class in @K(...). */
public record UserRequest(String value) {
    @Override public String toString() { return value; }
}
