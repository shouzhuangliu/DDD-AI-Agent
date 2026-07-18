package cn.bugstack.ai.trigger.service.capability.skill;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class SafeSkillArchiveValidator {

    private static final long MAX_ARCHIVE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final int MAX_FILES = 200;
    private static final Set<String> FORBIDDEN_SUFFIXES = Set.of(".exe", ".dll", ".bat", ".cmd", ".ps1", ".com");

    public ValidationReport validateAndExtract(InputStream archive, Path quarantineDirectory) throws IOException {
        byte[] raw = readBounded(archive, MAX_ARCHIVE_BYTES);
        String sha256 = hex(digest(raw));
        Path root = quarantineDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int fileCount = 0;
        long totalBytes = 0;
        boolean skillDocument = false;
        boolean manifest = false;
        List<String> files = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
                    throw new IllegalArgumentException("Unsafe ZIP path: " + name);
                }
                Path output = root.resolve(name).normalize();
                if (!output.startsWith(root)) throw new IllegalArgumentException("ZIP entry escapes quarantine: " + name);
                if (entry.isDirectory()) { Files.createDirectories(output); continue; }
                if (++fileCount > MAX_FILES) throw new IllegalArgumentException("Skill archive contains too many files");
                String lower = name.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_SUFFIXES.stream().anyMatch(lower::endsWith)) {
                    throw new IllegalArgumentException("Forbidden executable in Skill: " + name);
                }
                Files.createDirectories(output.getParent());
                long entryBytes = 0;
                try (OutputStream target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        entryBytes += read; totalBytes += read;
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw new IllegalArgumentException("Skill archive exceeds extraction limits");
                        }
                        target.write(buffer, 0, read);
                    }
                }
                files.add(name);
                if (name.equals("SKILL.md")) skillDocument = true;
                if (name.equals("skill.json")) manifest = true;
            }
        } catch (RuntimeException | IOException exception) {
            deleteExtractedFiles(root);
            throw exception;
        }
        if (!skillDocument || !manifest) {
            deleteExtractedFiles(root);
            throw new IllegalArgumentException("Skill ZIP requires root SKILL.md and skill.json");
        }
        return new ValidationReport(true, sha256, fileCount, totalBytes, List.copyOf(files));
    }

    private byte[] readBounded(InputStream source, long max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read; long total = 0;
        while ((read = source.read(buffer)) != -1) {
            total += read; if (total > max) throw new IllegalArgumentException("Skill ZIP exceeds upload limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private byte[] digest(byte[] raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String hex(byte[] value) { return java.util.HexFormat.of().formatHex(value); }

    private void deleteExtractedFiles(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(root)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record ValidationReport(boolean valid, String sha256, int fileCount, long totalBytes, List<String> files) {}
}
