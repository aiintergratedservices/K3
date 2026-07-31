package com.hackerai.supervisor.context;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe key-value context store shared across agents.
 * Allows agents to publish findings and consume results from
 * upstream agents without tight coupling.
 *
 * Keys are typically Class<?> objects or String identifiers.
 */
public class ContextMap {

    private final Map<Object, Object> store = new ConcurrentHashMap<>();

    // ─── Put operations ─────────────────────────────────────────

    public <T> void put(Class<T> key, T value) {
        store.put(Objects.requireNonNull(key), value);
    }

    public void put(String key, Object value) {
        store.put(Objects.requireNonNull(key), value);
    }

    public <T> void putIfAbsent(Class<T> key, T value) {
        store.putIfAbsent(key, value);
    }

    // ─── Get operations ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> key) {
        return (T) store.get(key);
    }

    public Object get(String key) {
        return store.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(Class<T> key, T defaultValue) {
        return (T) store.getOrDefault(key, defaultValue);
    }

    // ─── Query operations ───────────────────────────────────────

    public boolean containsKey(Object key) {
        return store.containsKey(key);
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }

    public int size() {
        return store.size();
    }

    public Set<Object> keySet() {
        return store.keySet();
    }

    // ─── Bulk operations ────────────────────────────────────────

    public void putAll(ContextMap other) {
        store.putAll(other.store);
    }

    public void putAll(Map<?, ?> map) {
        store.putAll(map);
    }

    public Map<Object, Object> asMap() {
        return Collections.unmodifiableMap(store);
    }

    // ─── Clear ──────────────────────────────────────────────────

    public void clear() {
        store.clear();
    }

    public Object remove(Object key) {
        return store.remove(key);
    }

    // ─── Utility ────────────────────────────────────────────────

    @Override
    public String toString() {
        var sb = new StringBuilder("ContextMap {");
        var first = true;
        for (var entry : store.entrySet()) {
            if (!first) sb.append(", ");
            var key = entry.getKey() instanceof Class<?> c
                ? c.getSimpleName()
                : entry.getKey().toString();
            sb.append(key).append("=").append(
                entry.getValue() != null
                    ? entry.getValue().toString().substring(0,
                        Math.min(50, entry.getValue().toString().length()))
                    : "null"
            );
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
