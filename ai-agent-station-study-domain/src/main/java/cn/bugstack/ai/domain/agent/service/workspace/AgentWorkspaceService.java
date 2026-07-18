package cn.bugstack.ai.domain.agent.service.workspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class AgentWorkspaceService {

    public Path resolveWorkDir(String agentId, String configuredWorkDir, String fallbackBaseDir) {
        if (configuredWorkDir != null && !configuredWorkDir.isBlank()) {
            return Path.of(configuredWorkDir).toAbsolutePath().normalize();
        }
        String safeAgentId = (agentId == null || agentId.isBlank()) ? "default-agent" : agentId.trim();
        return resolveProjectRoot(fallbackBaseDir)
                .resolve(".ma")
                .resolve("workspaces")
                .resolve(safeAgentId)
                .toAbsolutePath()
                .normalize();
    }

    public Path ensureAgentWorkspace(String agentId, String configuredWorkDir, String fallbackBaseDir) {
        Path workDir = resolveWorkDir(agentId, configuredWorkDir, fallbackBaseDir);
        try {
            Files.createDirectories(workDir);
            Files.createDirectories(workDir.resolve(".ma").resolve("skills"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize agent workspace: " + workDir, exception);
        }
        return workDir;
    }

    public Path syncSkills(String agentId, String configuredWorkDir, String fallbackBaseDir, List<String> skillIds) {
        Path workspace = ensureAgentWorkspace(agentId, configuredWorkDir, fallbackBaseDir);
        Path virtualSkillsRoot = workspace.resolve(".ma").resolve("skills").normalize();
        try {
            Files.createDirectories(virtualSkillsRoot);
            cleanupMissingSkills(virtualSkillsRoot, skillIds);
            for (String skillId : skillIds == null ? List.<String>of() : skillIds) {
                if (skillId == null || skillId.isBlank()) continue;
                Path source = resolveProjectRoot(fallbackBaseDir).resolve("skills").resolve(skillId.trim()).normalize();
                if (!Files.isDirectory(source)) {
                    log.warn("Skip syncing missing runtime skill {} from {}", skillId, source);
                    continue;
                }
                copyTree(source, virtualSkillsRoot.resolve(skillId.trim()).normalize());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to sync agent skills into workspace", exception);
        }
        return workspace;
    }

    public Path resolveProjectRoot(String fallbackBaseDir) {
        for (String candidate : List.of(
                fallbackBaseDir,
                System.getProperty("user.dir"),
                ".")) {
            Path root = detectProjectRoot(candidate);
            if (root != null) return root;
        }
        return Path.of(fallbackBaseDir == null || fallbackBaseDir.isBlank() ? "." : fallbackBaseDir)
                .toAbsolutePath()
                .normalize();
    }

    private Path detectProjectRoot(String baseDir) {
        if (baseDir == null || baseDir.isBlank()) return null;
        Path current = Path.of(baseDir).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("ai-agent-station-study-app"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private void cleanupMissingSkills(Path virtualSkillsRoot, List<String> skillIds) throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        for (String skillId : skillIds == null ? List.<String>of() : skillIds) {
            if (skillId != null && !skillId.isBlank()) expected.add(skillId.trim());
        }
        if (!Files.isDirectory(virtualSkillsRoot)) return;
        try (var paths = Files.list(virtualSkillsRoot)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> !expected.contains(path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            deleteTree(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) throw ioException;
            throw exception;
        }
    }

    private void copyTree(Path source, Path target) throws IOException {
        deleteTree(target);
        try (var paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path)).normalize();
                    if (!destination.startsWith(target)) {
                        throw new IllegalStateException("Invalid workspace skill path");
                    }
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) throw ioException;
            throw exception;
        }
    }

    private void deleteTree(Path target) throws IOException {
        if (target == null || !Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) throw ioException;
            throw exception;
        }
    }
}
