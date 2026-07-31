package com.hackerai.supervisor.model.value;

/**
 * Operating system information about the target or execution host.
 */
public record OSInfo(
    String os,
    String arch,
    String kernelVersion
) {

    public boolean isLinux() {
        return os.toLowerCase().contains("linux");
    }

    public boolean isWindows() {
        return os.toLowerCase().contains("windows");
    }

    public boolean isMacOS() {
        return os.toLowerCase().contains("mac");
    }

    @Override
    public String toString() {
        return os + " (" + arch + ") kernel " + kernelVersion;
    }
}
