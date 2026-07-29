package com.hackerai.supervisor.model.value;

import java.util.List;
import java.util.Map;

/**
 * Represents a discovered HTTP endpoint.
 */
public record Endpoint(
    String path,
    String method,
    Map<String, String> params,
    List<String> headers,
    boolean requiresAuth
) {

    public String toUrl(String base) {
        var url = base + path;
        if (!params.isEmpty()) {
            var query = String.join("&",
                params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .toList());
            url += "?" + query;
        }
        return url;
    }

    public boolean isInjectable() {
        return params.values().stream().anyMatch(v ->
            v.equals("string") || v.equals("text") || v.equals("id"));
    }

    @Override
    public String toString() {
        return method + " " + path + (requiresAuth ? " [auth]" : "");
    }
}
