package com.hackerai.supervisor.model.value;

import java.util.List;

/**
 * Represents an open network port with detected service and CVEs.
 */
public record OpenPort(
    int port,
    String protocol,
    String service,
    String version,
    List<String> cves
) {

    public boolean isHighValue() {
        return List.of("SSH", "RDP", "SMB", "MSSQL", "MySQL", "PostgreSQL", "Redis", "MongoDB")
            .contains(service);
    }

    public boolean isWebService() {
        return service.equals("HTTP") || service.equals("HTTPS");
    }

    @Override
    public String toString() {
        return port + "/" + protocol + " " + service
            + (version != null && !version.isEmpty() ? " (" + version + ")" : "")
            + (cves.isEmpty() ? "" : " CVEs: " + String.join(", ", cves));
    }
}
