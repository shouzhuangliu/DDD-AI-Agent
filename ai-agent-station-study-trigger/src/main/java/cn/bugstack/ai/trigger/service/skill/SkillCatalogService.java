package cn.bugstack.ai.trigger.service.skill;

import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolProperties;
import cn.bugstack.ai.infrastructure.dao.ISkillCatalogDao;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SkillCatalogService {

    @Resource
    private ISkillCatalogDao skillCatalogDao;
    @Resource
    private SkillScannerService skillScannerService;
    @Resource
    private ReActToolProperties properties;

    public List<SkillScannerService.SkillInfo> listSkills() {
        Map<String, SkillScannerService.SkillInfo> result = scanLocalSkills();
        try {
            skillCatalogDao.queryActiveReleasedSkills().forEach(row -> {
                    String skillId = String.valueOf(row.get("skill_id"));
                    SkillScannerService.SkillInfo runtime = skillScannerService
                            .readSkillMetadataFromWorkDir(properties.getWorkDir(), skillId);
                    SkillScannerService.SkillInfo skill = runtime != null ? runtime : SkillScannerService.SkillInfo.builder()
                            .skillId(skillId)
                            .skillName(String.valueOf(row.getOrDefault("skill_name", "")))
                            .description(String.valueOf(row.getOrDefault("description", "")))
                            .content("")
                            .build();
                    result.putIfAbsent(skill.getSkillId(), skill);
                });
        } catch (Exception exception) {
            log.warn("Failed to load released Skills from database; local Skills remain available", exception);
        }
        return new ArrayList<>(result.values());
    }

    private Map<String, SkillScannerService.SkillInfo> scanLocalSkills() {
        return new LinkedHashMap<>(skillScannerService.scanFromWorkDir(properties.getWorkDir()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        SkillScannerService.SkillInfo::getSkillId,
                        skill -> skill,
                        (first, ignored) -> first,
                        LinkedHashMap::new)));
    }
}
