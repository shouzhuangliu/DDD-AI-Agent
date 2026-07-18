package cn.bugstack.ai.domain.agent.service.tools.skill;
import cn.bugstack.ai.domain.agent.service.tools.core.AbstractReActTool;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContext;
import cn.bugstack.ai.domain.agent.service.tools.core.ReActToolContextHolder;
import cn.bugstack.ai.domain.agent.service.skills.SkillScannerService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * ReAct 内部工具：执行指定名称的 Skill。
 * <p>
 * 当 LLM 判断用户的请求可以通过某个 Skill 完成时，调用此工具。
 * 工具会返回 SKILL.md 全文（含 frontmatter），LLM 按手册步骤执行。
 * <p>
 * 工作模式（Progressive Disclosure）：
 * 1. LLM 看到 skill 列表（name + description）
 * 2. 决定调用 execute_skill("skill-id")
 * 3. 收到 SKILL.md 全文（步骤/附件路径/提示）
 * 4. 按步骤执行，需要附件时用 read_file 等工具
 *
 * @author ai-agent-station-study
 */
@Slf4j
@Component
public class SkillExecuteTool extends AbstractReActTool {

    @Resource
    private SkillScannerService skillScannerService;

    @Tool(description = "执行一个已注册的 Skill，返回该 Skill 的完整操作手册（SKILL.md）。参数 skillId 为 skill 目录名（如 demo-skill）。调用后 LLM 应按手册步骤执行。")
    public String executeSkill(@ToolParam(description = "Skill 目录名，如 demo-skill") String skillId) {
        String toolName = "execute_skill";
        emitAction(toolName, "执行 skill: " + skillId);

        if (skillId == null || skillId.isBlank()) {
            String msg = "skillId 不能为空";
            emitObservation(toolName, msg);
            return msg;
        }

        ReActToolContext context = ReActToolContextHolder.get();
        String currentWorkDir = context != null && context.getWorkDir() != null ? context.getWorkDir().toString() : ".";
        var skill = skillScannerService.readSkillFromWorkDir(currentWorkDir, skillId);
        if (skill == null) {
            String msg = "未找到 skill: " + skillId + "（请确认 skills/" + skillId + "/SKILL.md 存在）";
            emitObservation(toolName, msg);
            return msg;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("----- BEGIN SKILL.md -----\n");
        sb.append("# ").append(skill.getSkillName()).append("\n\n");
        sb.append(skill.getContent()).append("\n\n");
        sb.append("----- END SKILL.md -----\n\n");
        sb.append("请按以上手册步骤执行。如需读取附件，使用 read_file 工具。");

        String result = sb.toString();
        emitObservation(toolName, "已返回 SKILL.md，长度=" + result.length());
        return result;
    }
}
