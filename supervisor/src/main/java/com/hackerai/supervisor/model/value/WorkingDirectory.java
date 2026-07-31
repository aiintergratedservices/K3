package com.hackerai.supervisor.model.value;

/** Context-map key marker: the absolute/relative path to the target working directory. Referenced as WorkingDirectory.class in @K(...). */
public record WorkingDirectory(String value) {
    @Override public String toString() { return value; }
}
