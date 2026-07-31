package com.hackerai.supervisor.model.value;

/** Context-map key marker: the comma-separated list of tools detected/available for dispatch. Referenced as DetectedTools.class in @K(...). */
public record DetectedTools(String value) {
    @Override public String toString() { return value; }
}
