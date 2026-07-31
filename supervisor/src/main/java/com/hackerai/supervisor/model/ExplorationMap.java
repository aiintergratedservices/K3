package com.hackerai.supervisor.model;

import com.hackerai.supervisor.model.value.*;

import java.util.List;
import java.util.Map;

/**
 * Complete topological map of the target produced by ExplorerAgent.
 * Contents differ by mode (CODING vs PENTEST).
 */
public record ExplorationMap(
    List<String>          directories,
    List<Endpoint>        endpoints,
    List<String>          subdomains,
    List<OpenPort>        openPorts,
    List<DataFlow>        dataFlows,
    List<String>          entryPoints,
    Map<String, String>   fileSignatures
) {

    public List<Endpoint> injectableEndpoints() {
        return endpoints.stream()
            .filter(Endpoint::isInjectable)
            .toList();
    }

    public List<OpenPort> highValuePorts() {
        return openPorts.stream()
            .filter(OpenPort::isHighValue)
            .toList();
    }

    public List<DataFlow> vulnerableFlows() {
        return dataFlows.stream()
            .filter(DataFlow::isPotentialVulnerability)
            .toList();
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("Endpoints: ").append(endpoints.size()).append("\n");
        sb.append("Open Ports: ").append(openPorts.size()).append("\n");
        sb.append("Subdomains: ").append(subdomains.size()).append("\n");
        sb.append("Data Flows: ").append(dataFlows.size()).append("\n");
        sb.append("Entry Points: ").append(entryPoints.size());
        return sb.toString();
    }
}
