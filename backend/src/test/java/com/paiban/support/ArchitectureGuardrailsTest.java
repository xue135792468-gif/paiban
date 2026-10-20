package com.paiban.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 机械性规则的确定性检查。判断性规则（例如发布后基线只读）由对应的集成测试兜住。
 */
class ArchitectureGuardrailsTest {

    private static final Path SOURCES = Path.of("src/main/java");

    @Test
    void roleChecksGoThroughPermissionCodes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources()) {
            if (file.path().endsWith("identity/domain/AppUser.java")) {
                continue;
            }
            for (Line line : file.lines()) {
                if (line.text().contains("hasRole(") || line.text().contains("hasAnyRole(")) {
                    violations.add(line + "  直接比角色");
                }
            }
        }
        assertThat(violations)
                .as("权限判断用 hasAuthority(Permissions.X) 或 UserService.hasPermission；角色码只用于展示")
                .isEmpty();
    }

    @Test
    void permissionCodesComeFromConstants() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources()) {
            for (Line line : file.lines()) {
                if (line.text().contains("hasAuthority(\"")) {
                    violations.add(line + "  权限码请用 Permissions 常量");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    private List<SourceFile> sources() throws IOException {
        List<SourceFile> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path path : files.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                result.add(SourceFile.read(path));
            }
        }
        return result;
    }

    private record Line(String file, int number, String text) {
        @Override
        public String toString() {
            return file + ":" + number + " " + text.trim();
        }
    }

    private record SourceFile(String path, List<Line> lines) {

        static SourceFile read(Path source) throws IOException {
            String normalized = source.toString().replace('\\', '/');
            List<String> raw = Files.readAllLines(source, StandardCharsets.UTF_8);
            List<Line> lines = new ArrayList<>();
            for (int index = 0; index < raw.size(); index++) {
                lines.add(new Line(normalized, index + 1, raw.get(index)));
            }
            return new SourceFile(normalized, lines);
        }
    }
}
