package com.hackerai.supervisor.agent;

import com.hackerai.supervisor.annotation.AgentMethod;
import com.hackerai.supervisor.annotation.K;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One-pass target indexer. Scans the working directory to discover:
 * - Language and build system
 * - Test framework
 * - Exposed secrets / credentials
 * - Service endpoints (from config files, routes, OpenAPI specs)
 * - Environment variables
 * - Available CLI tools (checking PATH)
 * - Docker / Kubernetes presence
 * - Target OS from /etc/os-release or uname equivalents
 */
public interface ContextEngine {

    @AgentMethod(description = """
        Index the working directory to discover build system,
        language, frameworks, secrets, and attack surface.
        """)
    ContextSnapshot index(@K(WorkingDirectory.class) String workingDir);

    /**
     * Default implementation with heuristics-based detection.
     */
    static ContextEngine createDefault() {
        return (workingDir) -> {
            var path = Path.of(workingDir);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                throw new IllegalArgumentException(
                    "Working directory does not exist: " + workingDir);
            }

            var language       = detectLanguage(path);
            var buildSystem     = detectBuildSystem(path, language);
            var testFramework   = detectTestFramework(path, language);
            var frameworks      = detectFrameworks(path, language);
            var secrets         = findSecrets(path);
            var endpoints       = findEndpoints(path);
            var envVars         = loadEnvFile(path);
            var tools           = detectAvailableTools();
            var hasDocker       = findFile(path, "Dockerfile").isPresent()
                                || findFile(path, "docker-compose.yml").isPresent()
                                || findFile(path, "docker-compose.yaml").isPresent();
            var hasKubernetes   = findFileGlob(path, "**/*.yaml").stream()
                                .anyMatch(f -> {
                                    try {
                                        return Files.readString(f).contains("apiVersion:");
                                    } catch (IOException e) { return false; }
                                });
            var osInfo          = detectOS();

            return new ContextSnapshot(
                language, buildSystem, testFramework,
                frameworks, secrets, endpoints, envVars,
                tools, hasDocker, hasKubernetes, osInfo
            );
        };
    }

    // ─── Language Detection ────────────────────────────────────────

    private static String detectLanguage(Path root) {
        if (findFile(root, "pom.xml").isPresent()
            || findFile(root, "build.gradle").isPresent()
            || findFile(root, "build.gradle.kts").isPresent()) return "java";
        if (findFile(root, "Cargo.toml").isPresent())              return "rust";
        if (findFile(root, "go.mod").isPresent())                  return "go";
        if (findFile(root, "package.json").isPresent())            return "javascript";
        if (findFile(root, "pyproject.toml").isPresent()
            || findFile(root, "setup.py").isPresent()
            || findFile(root, "requirements.txt").isPresent())     return "python";
        if (findFile(root, "Gemfile").isPresent())                 return "ruby";
        if (findFile(root, "composer.json").isPresent())           return "php";
        if (findFile(root, "CMakeLists.txt").isPresent())          return "cpp";
        if (findFile(root, "Makefile").isPresent()
            && findFileGlob(root, "*.c").size() > 0)               return "c";
        // Fallback: count file extensions
        return detectLanguageByExtension(root);
    }

    private static String detectLanguageByExtension(Path root) {
        var counts = new HashMap<String, Integer>();
        try (var files = Files.walk(root, 5)) {
            files.filter(Files::isRegularFile)
                 .map(p -> {
                     var name = p.getFileName().toString();
                     int dot = name.lastIndexOf('.');
                     return dot > 0 ? name.substring(dot) : "";
                 })
                 .filter(ext -> !ext.isEmpty())
                 .forEach(ext -> counts.merge(ext, 1, Integer::sum));
        } catch (IOException ignored) {}

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .map(ext -> switch (ext) {
                case ".java"    -> "java";
                case ".py"      -> "python";
                case ".js"      -> "javascript";
                case ".ts"      -> "typescript";
                case ".rs"      -> "rust";
                case ".go"      -> "go";
                case ".rb"      -> "ruby";
                case ".php"     -> "php";
                case ".c"       -> "c";
                case ".cpp",".cc",".cxx","*.hpp" -> "cpp";
                case ".cs"      -> "csharp";
                default         -> "unknown";
            })
            .orElse("unknown");
    }

    // ─── Build System Detection ───────────────────────────────────

    private static String detectBuildSystem(Path root, String lang) {
        return switch (lang) {
            case "java" -> {
                if (findFile(root, "pom.xml").isPresent()) yield "maven";
                if (findFile(root, "build.gradle").isPresent()
                    || findFile(root, "build.gradle.kts").isPresent()) yield "gradle";
                yield "javac";
            }
            case "javascript" -> {
                if (findFile(root, "yarn.lock").isPresent())   yield "yarn";
                if (findFile(root, "pnpm-lock.yaml").isPresent()) yield "pnpm";
                yield "npm";
            }
            case "python"  -> {
                if (findFile(root, "poetry.lock").isPresent())  yield "poetry";
                if (findFile(root, "Pipfile").isPresent())      yield "pipenv";
                yield "pip";
            }
            case "rust"    -> "cargo";
            case "go"      -> "go build";
            case "ruby"    -> "bundler";
            default        -> "make";
        };
    }

    // ─── Test Framework Detection ─────────────────────────────────

    private static String detectTestFramework(Path root, String lang) {
        return switch (lang) {
            case "java" -> {
                if (findFileGlob(root, "**/*Test.java").size() > 10
                    || findFile(root, "**/test/**").isPresent()) {
                    if (hasDependency(root, "junit-jupiter"))    yield "junit5";
                    if (hasDependency(root, "junit"))            yield "junit4";
                    if (hasDependency(root, "testng"))           yield "testng";
                }
                yield "none";
            }
            case "javascript" -> {
                if (findFile(root, "jest.config.js").isPresent()
                    || findFile(root, "jest.config.ts").isPresent()) yield "jest";
                if (findFile(root, "vitest.config.ts").isPresent())  yield "vitest";
                if (findFile(root, "karma.conf.js").isPresent())     yield "karma";
                if (findFile(root, ".mocharc.yml").isPresent()
                    || findFile(root, ".mocharc.js").isPresent())    yield "mocha";
                yield "none";
            }
            case "python" -> {
                if (findFile(root, "pytest.ini").isPresent()
                    || findFile(root, "pyproject.toml").isPresent()
                       && fileContains(root, "pyproject.toml", "pytest")) yield "pytest";
                if (findFile(root, "setup.cfg").isPresent()
                    && fileContains(root, "setup.cfg", "unittest"))       yield "unittest";
                yield "none";
            }
            case "rust"   -> "cargo test";
            case "go"     -> "go test";
            default       -> "none";
        };
    }

    // ─── Framework Detection ──────────────────────────────────────

    private static List<String> detectFrameworks(Path root, String lang) {
        var frameworks = new ArrayList<String>();
        try (var files = Files.walk(root, 3).filter(Files::isRegularFile)) {
            files.forEach(f -> {
                var name = f.getFileName().toString();
                switch (lang) {
                    case "java" -> {
                        if (name.equals("pom.xml") || name.equals("build.gradle")) {
                            try {
                                var content = Files.readString(f);
                                if (content.contains("spring-boot")) frameworks.add("spring-boot");
                                if (content.contains("quarkus"))    frameworks.add("quarkus");
                                if (content.contains("micronaut"))  frameworks.add("micronaut");
                                if (content.contains("vertx"))      frameworks.add("vertx");
                                if (content.contains("jakarta"))    frameworks.add("jakarta-ee");
                            } catch (IOException ignored) {}
                        }
                    }
                    case "javascript", "typescript" -> {
                        if (name.equals("package.json")) {
                            try {
                                var content = Files.readString(f);
                                if (content.contains("express"))  frameworks.add("express");
                                if (content.contains("next"))     frameworks.add("nextjs");
                                if (content.contains("nuxt"))     frameworks.add("nuxt");
                                if (content.contains("react"))    frameworks.add("react");
                                if (content.contains("angular"))  frameworks.add("angular");
                                if (content.contains("vue"))      frameworks.add("vue");
                                if (content.contains("fastify"))  frameworks.add("fastify");
                            } catch (IOException ignored) {}
                        }
                    }
                    case "python" -> {
                        if (name.equals("requirements.txt") || name.equals("pyproject.toml")) {
                            try {
                                var content = Files.readString(f);
                                if (content.contains("flask"))    frameworks.add("flask");
                                if (content.contains("django"))   frameworks.add("django");
                                if (content.contains("fastapi"))  frameworks.add("fastapi");
                                if (content.contains("aiohttp"))  frameworks.add("aiohttp");
                            } catch (IOException ignored) {}
                        }
                    }
                }
            });
        } catch (IOException ignored) {}
        return frameworks;
    }

    // ─── Secret Scanning ──────────────────────────────────────────

    static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("(?i)(?:aws_access_key_id|aws_secret_access_key)\\s*[:=]\\s*['\"]?([A-Za-z0-9+/=]{16,})"),
        Pattern.compile("(?i)(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,}"),          // GitHub tokens
        Pattern.compile("(?i)(?:sk-[a-zA-Z0-9]{20,}|pk-[a-zA-Z0-9]{20,})"),         // OpenAI keys
        Pattern.compile("(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        Pattern.compile("(?i)(?:password|passwd|pwd)\\s*[:=]\\s*['\"]?[^'\"]{8,}"),
        Pattern.compile("(?i)(?:api[_-]?key|apikey)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-]{16,}"),
        Pattern.compile("(?i)mongodb\\+srv://[^:\\s]+:[^@\\s]+@"),
        Pattern.compile("(?i)postgresql://[^:\\s]+:[^@\\s]+@"),
        Pattern.compile("(?i)redis://[^:@\\s]+:[^@\\s]+@"),
        Pattern.compile("(?i)JWT_SECRET\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-]{16,}")
    );

    private static List<SecretCandidate> findSecrets(Path root) {
        var secrets = new ArrayList<SecretCandidate>();
        try (var files = Files.walk(root, 8)
             .filter(Files::isRegularFile)
             .filter(f -> {
                 var name = f.getFileName().toString().toLowerCase();
                 return !name.endsWith(".class")
                     && !name.endsWith(".jar")
                     && !name.endsWith(".png")
                     && !name.endsWith(".jpg")
                     && !name.endsWith(".svg")
                     && !name.startsWith(".");
             })) {
            files.forEach(f -> {
                try (var lines = Files.lines(f)) {
                    var lineList = lines.toList();
                    for (int i = 0; i < lineList.size(); i++) {
                        var line = lineList.get(i);
                        for (var pattern : SECRET_PATTERNS) {
                            var m = pattern.matcher(line);
                            if (m.find()) {
                                secrets.add(new SecretCandidate(
                                    root.relativize(f).toString(),
                                    i + 1,
                                    pattern.pattern().substring(0, 30) + "...",
                                    line.trim().substring(0, Math.min(40, line.trim().length()))
                                ));
                            }
                        }
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return secrets;
    }

    // ─── Endpoint Discovery ───────────────────────────────────────

    private static List<String> findEndpoints(Path root) {
        var endpoints = new HashSet<String>();
        try (var files = Files.walk(root, 6).filter(Files::isRegularFile)) {
            files.forEach(f -> {
                var name = f.getFileName().toString().toLowerCase();
                try {
                    var content = Files.readString(f);
                    // OpenAPI/Swagger
                    var swaggerMatch = Pattern.compile("paths\\s*:").matcher(content);
                    if (swaggerMatch.find()) endpoints.add("OpenAPI spec: " + root.relativize(f));
                    // Spring request mappings
                    var springPattern = Pattern.compile(
                        "@(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)\\([\"']([^\"']+)[\"']");
                    var sm = springPattern.matcher(content);
                    while (sm.find()) endpoints.add(sm.group(2));
                    // Express/Fastify routes
                    var expressPattern = Pattern.compile(
                        "(?:app|router)\\.(?:get|post|put|delete|patch)\\([\"']([^\"']+)[\"']");
                    var em = expressPattern.matcher(content);
                    while (em.find()) endpoints.add(em.group(1));
                    // Flask/Django routes
                    var flaskPattern = Pattern.compile("@(?:app|blueprint)\\.route\\([\"']([^\"']+)[\"']");
                    var fm = flaskPattern.matcher(content);
                    while (fm.find()) endpoints.add(fm.group(1));
                    // Gin (Go)
                    var ginPattern = Pattern.compile("r\\.(?:GET|POST|PUT|DELETE|PATCH)\\([\"']([^\"']+)[\"']");
                    var gm = ginPattern.matcher(content);
                    while (gm.find()) endpoints.add(gm.group(1));
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return new ArrayList<>(endpoints);
    }

    // ─── Environment Variables ────────────────────────────────────

    private static Map<String, String> loadEnvFile(Path root) {
        var envFile = findFile(root, ".env");
        if (envFile.isEmpty()) return Map.of();
        var env = new HashMap<String, String>();
        try (var lines = Files.lines(envFile.get())) {
            lines.map(String::trim)
                 .filter(l -> !l.startsWith("#") && l.contains("="))
                 .forEach(l -> {
                     int eq = l.indexOf('=');
                     var key = l.substring(0, eq).trim();
                     var val = l.substring(eq + 1).trim();
                     env.put(key, val);
                 });
        } catch (IOException ignored) {}
        return Map.copyOf(env);
    }

    // ─── Tool Detection ───────────────────────────────────────────

    private static List<String> detectAvailableTools() {
        var tools = new ArrayList<String>();
        var commonTools = List.of(
            "nmap", "sqlmap", "nuclei", "metasploit", "msfconsole",
            "nikto", "gobuster", "ffuf", "dirb", "hydra", "john",
            "hashcat", "aircrack-ng", "bettercap", "burpsuite",
            "bloodhound-python", "certipy", "impacket", "responder",
            "ldapsearch", "smbclient", "enum4linux", "crackmapexec",
            "netexec", "proxychains", "socat", "nc", "ncat",
            "docker", "kubectl", "helm", "terraform", "ansible",
            "git", "curl", "wget", "openssl", "python3", "node",
            "java", "mvn", "gradle", "go", "rustc", "cargo"
        );
        for (var tool : commonTools) {
            try {
                var proc = new ProcessBuilder("which", tool)
                    .redirectErrorStream(true)
                    .start();
                if (proc.waitFor() == 0) tools.add(tool);
            } catch (Exception ignored) {}
        }
        return tools;
    }

    // ─── OS Detection ─────────────────────────────────────────────

    private static OSInfo detectOS() {
        var os = System.getProperty("os.name", "unknown");
        var arch = System.getProperty("os.arch", "unknown");
        var kernel = "unknown";
        try {
            var proc = new ProcessBuilder("uname", "-r")
                .redirectErrorStream(true)
                .start();
            kernel = new String(proc.getInputStream().readAllBytes()).trim();
        } catch (Exception ignored) {}
        return new OSInfo(os, arch, kernel);
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private static Optional<Path> findFile(Path root, String name) {
        try (var files = Files.walk(root, 3)) {
            return files.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().equals(name))
                        .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<Path> findFileGlob(Path root, String glob) {
        var matcher = root.getFileSystem().getPathMatcher("glob:" + glob);
        try (var files = Files.walk(root, 8)) {
            return files.filter(Files::isRegularFile)
                        .filter(matcher::matches)
                        .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean fileContains(Path root, String fileName, String substring) {
        return findFile(root, fileName)
            .map(f -> {
                try { return Files.readString(f).contains(substring); }
                catch (IOException e) { return false; }
            })
            .orElse(false);
    }

    private static boolean hasDependency(Path root, String dep) {
        return findFile(root, "pom.xml")
            .map(f -> {
                try { return Files.readString(f).contains("<artifactId>" + dep + "</artifactId>"); }
                catch (IOException e) { return false; }
            })
            .orElse(false);
    }

    // Marker keys
    record WorkingDirectory(String v) {}
}
