package com.hackerai.supervisor.dispatch;

import java.util.*;

/**
 * Registry of known security and development tools.
 * Maps tool names to their binary path, default arguments,
 * and timeout configuration.
 */
public class ToolRegistry {

    private final Map<String, ToolDispatcher.ToolConfig> tools;

    public ToolRegistry(Map<String, ToolDispatcher.ToolConfig> tools) {
        this.tools = Map.copyOf(tools);
    }

    public static ToolRegistry createDefault() {
        var registry = new HashMap<String, ToolDispatcher.ToolConfig>();

        // ─── Reconnaissance Tools ─────────────────────────────
        registry.put("nmap",     ToolDispatcher.ToolConfig.of("nmap",     300_000));
        registry.put("masscan",  ToolDispatcher.ToolConfig.of("masscan",  120_000,
            "--rate=1000"));
        registry.put("rustscan", ToolDispatcher.ToolConfig.of("rustscan", 120_000,
            "-a"));

        // ─── Web Application Scanners ─────────────────────────
        registry.put("gobuster", ToolDispatcher.ToolConfig.of("gobuster", 120_000,
            "dir"));
        registry.put("ffuf",     ToolDispatcher.ToolConfig.of("ffuf",     120_000));
        registry.put("dirb",     ToolDispatcher.ToolConfig.of("dirb",     120_000));
        registry.put("nikto",    ToolDispatcher.ToolConfig.of("nikto",    300_000,
            "-h"));

        // ─── Vulnerability Scanners ───────────────────────────
        registry.put("nuclei",   ToolDispatcher.ToolConfig.of("nuclei",   300_000,
            "-silent", "-no-color"));
        registry.put("wpscan",   ToolDispatcher.ToolConfig.of("wpscan",   120_000,
            "--no-banner", "--random-user-agent"));

        // ─── SQL Injection ────────────────────────────────────
        registry.put("sqlmap",   ToolDispatcher.ToolConfig.of("sqlmap",   600_000,
            "--batch", "--random-agent", "--threads=4"));

        // ─── Brute Force / Credential Stuffing ────────────────
        registry.put("hydra",    ToolDispatcher.ToolConfig.of("hydra",    600_000));
        registry.put("john",     ToolDispatcher.ToolConfig.of("john",     600_000));
        registry.put("hashcat",  ToolDispatcher.ToolConfig.of("hashcat",  600_000));

        // ─── Network / Protocol Tools ─────────────────────────
        registry.put("smbclient", ToolDispatcher.ToolConfig.of("smbclient", 30_000,
            "-N", "-L"));
        registry.put("enum4linux", ToolDispatcher.ToolConfig.of("enum4linux", 60_000,
            "-a"));
        registry.put("crackmapexec", ToolDispatcher.ToolConfig.of("crackmapexec", 120_000));
        registry.put("netexec",  ToolDispatcher.ToolConfig.of("netexec",  120_000));
        registry.put("ldapsearch", ToolDispatcher.ToolConfig.of("ldapsearch", 30_000));
        registry.put("responder", ToolDispatcher.ToolConfig.of("responder", 300_000,
            "-I", "eth0"));
        registry.put("impacket",  ToolDispatcher.ToolConfig.of("impacket", 120_000));

        // ─── Active Directory ─────────────────────────────────
        registry.put("bloodhound-python", ToolDispatcher.ToolConfig.of("bloodhound-python", 120_000,
            "-d"));
        registry.put("certipy",  ToolDispatcher.ToolConfig.of("certipy",  120_000));

        // ─── Metasploit ───────────────────────────────────────
        registry.put("msfconsole", ToolDispatcher.ToolConfig.of("msfconsole", 300_000,
            "-q", "-x"));
        registry.put("metasploit", ToolDispatcher.ToolConfig.of("msfconsole", 300_000));

        // ─── General Utilities ────────────────────────────────
        registry.put("curl",    ToolDispatcher.ToolConfig.of("curl",     60_000,
            "-s", "--connect-timeout", "10"));
        registry.put("wget",    ToolDispatcher.ToolConfig.of("wget",     60_000,
            "-q", "-O", "-"));
        registry.put("nc",      ToolDispatcher.ToolConfig.of("nc",       120_000,
            "-v"));
        registry.put("ncat",    ToolDispatcher.ToolConfig.of("ncat",     120_000,
            "-v"));
        registry.put("openssl", ToolDispatcher.ToolConfig.of("openssl",  30_000));
        registry.put("socat",   ToolDispatcher.ToolConfig.of("socat",    120_000));
        registry.put("proxychains", ToolDispatcher.ToolConfig.of("proxychains", 300_000));

        // ─── Development / Build Tools ────────────────────────
        registry.put("mvn",     ToolDispatcher.ToolConfig.of("mvn",      600_000,
            "--batch-mode"));
        registry.put("gradle",  ToolDispatcher.ToolConfig.of("gradle",   600_000));
        registry.put("npm",     ToolDispatcher.ToolConfig.of("npm",      120_000));
        registry.put("yarn",    ToolDispatcher.ToolConfig.of("yarn",     120_000));
        registry.put("pnpm",    ToolDispatcher.ToolConfig.of("pnpm",     120_000));
        registry.put("cargo",   ToolDispatcher.ToolConfig.of("cargo",    600_000));
        registry.put("go",      ToolDispatcher.ToolConfig.of("go",       600_000));
        registry.put("python3", ToolDispatcher.ToolConfig.of("python3",  60_000));
        registry.put("pip",     ToolDispatcher.ToolConfig.of("pip",      120_000));
        registry.put("make",    ToolDispatcher.ToolConfig.of("make",     300_000));
        registry.put("docker",  ToolDispatcher.ToolConfig.of("docker",   300_000));
        registry.put("kubectl", ToolDispatcher.ToolConfig.of("kubectl",  60_000));
        registry.put("terraform", ToolDispatcher.ToolConfig.of("terraform", 300_000));
        registry.put("ansible", ToolDispatcher.ToolConfig.of("ansible",  300_000));
        registry.put("git",     ToolDispatcher.ToolConfig.of("git",      60_000));

        // ─── Linters ─────────────────────────────────────────
        registry.put("eslint",  ToolDispatcher.ToolConfig.of("npx",      120_000,
            "eslint", "."));
        registry.put("prettier", ToolDispatcher.ToolConfig.of("npx",     60_000,
            "prettier", "--check", "."));
        registry.put("checkstyle", ToolDispatcher.ToolConfig.of("mvn",   120_000,
            "checkstyle:check"));
        registry.put("flake8",  ToolDispatcher.ToolConfig.of("flake8",   60_000));
        registry.put("ruff",    ToolDispatcher.ToolConfig.of("ruff",     60_000,
            "check", "."));

        return new ToolRegistry(registry);
    }

    public ToolDispatcher.ToolConfig getConfig(String toolName) {
        // Direct match
        var config = tools.get(toolName);
        if (config != null) return config;

        // Fuzzy match: try lowercase
        config = tools.get(toolName.toLowerCase());
        if (config != null) return config;

        // Check if it's a known binary name
        for (var entry : tools.entrySet()) {
            if (entry.getValue().binary().equals(toolName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    public Set<String> getAvailableTools() {
        return tools.keySet();
    }

    public boolean hasTool(String toolName) {
        return getConfig(toolName) != null;
    }
}
