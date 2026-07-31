package com.hackerai.supervisor.agent;

import com.hackerai.supervisor.annotation.AgentMethod;
import com.hackerai.supervisor.annotation.K;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.*;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reconnaissance & discovery agent. In PENTEST mode, scans for
 * subdomains, open ports, running services, directory structure,
 * and parameter endpoints. In CODING mode, maps module layout,
 * imports, and data flow paths.
 *
 * Multiple explorer instances can fan out in parallel for large
 * targets (e.g., port scanning + subdomain enumeration + directory
 * busting concurrently).
 */
public interface ExplorerAgent {

    @AgentMethod(description = """
        Discover the full topology of the target.
        In PENTEST mode: subdomains, open ports, services,
        directory structure, parameter endpoints.
        In CODING mode: module layout, imports, data flow.
        Multiple explorers can run concurrently.
        """)
    ExplorationMap explore(
        @K(WorkingDirectory.class) String workingDir,
        @K(ContextSnapshot.class)  ContextSnapshot context,
        @K(OperationMode.class)    String mode
    );

    static ExplorerAgent createDefault() {
        return (workingDir, context, mode) -> {
            var modeEnum = OperationMode.fromString(mode);

            if (modeEnum.isPentest()) {
                return explorePentest(Path.of(workingDir), context);
            } else {
                return exploreCodebase(Path.of(workingDir), context);
            }
        };
    }

    // ─── PENTEST Exploration ──────────────────────────────────────

    private static ExplorationMap explorePentest(Path root, ContextSnapshot ctx) {
        var endpoints   = new ArrayList<Endpoint>();
        var openPorts   = new ArrayList<OpenPort>();
        var subdomains  = new ArrayList<String>();
        var entryPoints = new ArrayList<String>();
        var signatures  = new HashMap<String, String>();

        var threadPool = Executors.newVirtualThreadPerTaskExecutor();
        var tasks = new ArrayList<CompletableFuture<?>>();

        // Task 1: Port scan common ports
        tasks.add(CompletableFuture.runAsync(() -> {
            var commonPorts = List.of(
                21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443,
                445, 993, 995, 1433, 1521, 2049, 2375, 2376, 3306,
                3389, 5432, 5900, 5985, 5986, 6379, 8080, 8443, 9000,
                9090, 27017
            );
            var host = extractHost(root);
            for (var port : commonPorts) {
                try (var sock = new Socket()) {
                    sock.connect(new InetSocketAddress(host, port), 300);
                    var service = guessService(port);
                    var version = tryGrabBanner(sock, port);
                    openPorts.add(new OpenPort(port, "tcp", service, version, List.of()));
                } catch (Exception ignored) {}
            }
        }, threadPool));

        // Task 2: Directory busting
        tasks.add(CompletableFuture.runAsync(() -> {
            var commonDirs = List.of(
                "/admin", "/api", "/assets", "/backup", "/config",
                "/dashboard", "/debug", "/.env", "/health", "/info",
                "/login", "/metrics", "/robots.txt", "/sitemap.xml",
                "/static", "/status", "/swagger", "/uploads", "/vendor",
                "/v1", "/v2", "/wp-admin", "/wp-content"
            );
            var baseUrl = extractBaseUrl(root);
            for (var dir : commonDirs) {
                try {
                    var url = new URI(baseUrl + dir).toURL();
                    var conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int code = conn.getResponseCode();
                    if (code < 400) {
                        endpoints.add(new Endpoint(
                            dir, "GET", Map.of(), List.of(), code == 401 || code == 403
                        ));
                        entryPoints.add(dir);
                    }
                } catch (Exception ignored) {}
            }
        }, threadPool));

        // Task 3: Subdomain discovery
        tasks.add(CompletableFuture.runAsync(() -> {
            var domain = extractDomain(root);
            if (domain != null) {
                var commonSubs = List.of(
                    "www", "api", "admin", "mail", "ftp", "dev",
                    "staging", "test", "vpn", "git", "jenkins",
                    "jira", "confluence", "grafana", "prometheus",
                    "kibana", "elastic", "redis", "mysql", "db"
                );
                for (var sub : commonSubs) {
                    try {
                        var fullDomain = sub + "." + domain;
                        var addr = InetAddress.getByName(fullDomain);
                        subdomains.add(fullDomain + " (" + addr.getHostAddress() + ")");
                    } catch (Exception ignored) {}
                }
            }
        }, threadPool));

        // Wait for all tasks
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        threadPool.shutdown();

        return new ExplorationMap(
            List.of(), endpoints, subdomains, openPorts,
            List.of(), entryPoints, signatures
        );
    }

    // ─── CODING Exploration ───────────────────────────────────────

    private static ExplorationMap exploreCodebase(Path root, ContextSnapshot ctx) {
        var directories  = new ArrayList<String>();
        var endpoints    = new ArrayList<Endpoint>();
        var dataFlows    = new ArrayList<DataFlow>();
        var entryPoints  = new ArrayList<String>();
        var signatures   = new HashMap<String, String>();

        try (var files = Files.walk(root, 10).filter(Files::isRegularFile)) {
            files.forEach(f -> {
                var rel = root.relativize(f).toString();
                var parent = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : ".";

                if (!directories.contains(parent)) {
                    directories.add(parent);
                }

                // Entry point detection
                var name = f.getFileName().toString().toLowerCase();
                if (name.equals("main.java") || name.equals("main.py")
                    || name.equals("index.js") || name.equals("app.js")
                    || name.equals("server.js") || name.equals("main.go")
                    || name.equals("main.rs") || name.equals("__init__.py")
                    || name.equals("manage.py") || name.equals("cli.py")) {
                    entryPoints.add(rel);
                }

                // File hash for change detection
                try {
                    var hash = computeSimpleHash(f);
                    signatures.put(rel, hash);
                } catch (Exception ignored) {}

                // Data flow analysis (basic: imports / requires)
                if (name.endsWith(".java") || name.endsWith(".py")
                    || name.endsWith(".js") || name.endsWith(".ts")
                    || name.endsWith(".go") || name.endsWith(".rs")) {
                    try (var lines = Files.lines(f)) {
                        var content = lines.collect(Collectors.joining("\n"));
                        // Detect source->sink data flows from annotations/comments
                        var sourcePattern = Pattern.compile(
                            "@(?:RequestBody|RequestParam|PathVariable|GetMapping|PostMapping)");
                        var sinkPattern = Pattern.compile(
                            "(?:repository|dao|database|executeQuery|runQuery)\\.\\w+");
                        var sm = sourcePattern.matcher(content);
                        var snk = sinkPattern.matcher(content);
                        if (sm.find() && snk.find()) {
                            dataFlows.add(new DataFlow(
                                sm.group(),
                                snk.group(),
                                "user_input",
                                true
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (IOException ignored) {}

        return new ExplorationMap(
            directories, endpoints, List.of(), List.of(),
            dataFlows, entryPoints, signatures
        );
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private static String extractHost(Path root) {
        // Try git remote or fallback to localhost
        try {
            var gitConfig = root.resolve(".git").resolve("config");
            if (Files.exists(gitConfig)) {
                var content = Files.readString(gitConfig);
                var m = Pattern.compile("url\\s*=\\s*(?:https?://)?([^:/\\s]+)")
                               .matcher(content);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static String extractBaseUrl(Path root) {
        var host = extractHost(root);
        return "http://" + host;
    }

    private static String extractDomain(Path root) {
        var host = extractHost(root);
        if (host.equals("127.0.0.1") || host.equals("localhost")) return null;
        var parts = host.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return host;
    }

    private static String guessService(int port) {
        return switch (port) {
            case 21 -> "FTP";
            case 22 -> "SSH";
            case 23 -> "Telnet";
            case 25 -> "SMTP";
            case 53 -> "DNS";
            case 80, 8080 -> "HTTP";
            case 110 -> "POP3";
            case 111 -> "RPC";
            case 135 -> "MSRPC";
            case 139 -> "NetBIOS";
            case 143 -> "IMAP";
            case 443, 8443 -> "HTTPS";
            case 445 -> "SMB";
            case 993 -> "IMAPS";
            case 995 -> "POP3S";
            case 1433 -> "MSSQL";
            case 1521 -> "Oracle DB";
            case 2049 -> "NFS";
            case 2375 -> "Docker (unencrypted)";
            case 2376 -> "Docker (TLS)";
            case 3306 -> "MySQL";
            case 3389 -> "RDP";
            case 5432 -> "PostgreSQL";
            case 5900 -> "VNC";
            case 5985 -> "WinRM HTTP";
            case 5986 -> "WinRM HTTPS";
            case 6379 -> "Redis";
            case 9000 -> "PHP-FPM";
            case 9090 -> "Prometheus/Grafana";
            case 27017 -> "MongoDB";
            default -> "unknown";
        };
    }

    private static String tryGrabBanner(Socket sock, int port) {
        try {
            sock.setSoTimeout(2000);
            if (port == 80 || port == 8080 || port == 443 || port == 8443) {
                var writer = new java.io.PrintWriter(sock.getOutputStream(), true);
                writer.println("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            }
            var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(sock.getInputStream()));
            return reader.lines().findFirst().orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String computeSimpleHash(Path f) throws IOException {
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("MD5"); }
        catch (java.security.NoSuchAlgorithmException e) { return "error"; }
        try (var is = Files.newInputStream(f)) {
            var buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        } catch (Exception e) {
            return "error";
        }
        var bytes = digest.digest();
        var sb = new StringBuilder();
        for (var b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
