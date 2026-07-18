package cn.bugstack.ai.test.agent.capability;

import cn.bugstack.ai.trigger.service.capability.skill.SafeSkillArchiveValidator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SafeSkillArchiveValidatorTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void validatesAndExtractsSkillPackage() throws Exception {
        byte[] zip = archive(new String[][]{{"skill.json", "{\"id\":\"demo\",\"version\":\"1.0.0\"}"}, {"SKILL.md", "# Demo\nSafe skill"}});
        var report = new SafeSkillArchiveValidator().validateAndExtract(
                new ByteArrayInputStream(zip), folder.newFolder("valid").toPath());
        assertTrue(report.valid());
        assertEquals(2, report.fileCount());
        assertEquals(64, report.sha256().length());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZipSlip() throws Exception {
        byte[] zip = archive(new String[][]{{"../escape.txt", "bad"}, {"SKILL.md", "x"}});
        new SafeSkillArchiveValidator().validateAndExtract(new ByteArrayInputStream(zip), folder.newFolder("slip").toPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingSkillDocument() throws Exception {
        byte[] zip = archive(new String[][]{{"skill.json", "{}"}});
        new SafeSkillArchiveValidator().validateAndExtract(new ByteArrayInputStream(zip), folder.newFolder("missing").toPath());
    }

    private byte[] archive(String[][] files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(file[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
