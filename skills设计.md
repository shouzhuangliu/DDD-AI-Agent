emoryCenter Skills 设计与加载 — 复现文档

覆盖：Skill domain · frontmatter 解析 · 存储布局 · 安装来源 · Agent 工作区同步 · 运行时加载 · Progressive Disclosure 三层 · 脚本执行机制 · 只读保护 · 8 个难点

---

1. 核心设计理念

Skill 对齐业界标准（Anthropic Claude Code / skills.sh / Cursor）：

> **一个 Skill = 一个文件夹 `skills/{id}/`，入口文件为 `SKILL.md`（带 YAML frontmatter 的 markdown 操作手册）**

关键点：Progressive Disclosure（渐进式披露）——Skill 不是直接执行的代码，而是一份操作手册。LLM 先用 function call 触发 skill 工具，拿到 SKILL.md 全文，再按手册分步执行，需要附件时懒加载。

LLM 判断需要 commit-message skill
  ↓  function call: commit_message()
SkillExecutor.execute()
  ↓  返回 SKILL.md 全文 + 附件清单
LLM 按手册分步执行：
  git diff → read_skill_file("scripts/format.py") → shell_exec("python scripts/format.py")

---

2. Skill Domain 对象

public class Skill {
    public enum Type { SKILL }   // 历史字段，统一为 SKILL，保留供 RowMapper 反序列化

    String id;           // "skl_" + 12位随机串（平台库 Skill）
    String userId;       // 创建者（审计）
    String groupId;      // 工作组隔离边界（多租户）
    String name;         // frontmatter name 或手动填写
    String description;  // frontmatter description，200 字截断后展示给 LLM
    String source;       // "github:<owner>/<repo>[@skill]" | "upload:<filename>" | "manual" | "workspace-skills"
    String filePath;     // 相对 storage-root："groups/{groupId}/skills/{id}/SKILL.md"
    boolean enabled;
    Instant createdAt, updatedAt;
}

**`source` 字段的四种取值**：

值
含义
github:owner/repo@skill
从 GitHub / skills.sh 安装
upload:filename.zip
上传 zip 或 .md
manual
在 UI 直接编辑
workspace-skills
工作区 skills/ 下无 DB 行的自建包（自动识别）

---

3. Frontmatter 格式与解析

3.1 SKILL.md 格式


---
name: commit-message
description: Generates conventional commits from git diff
version: 1.0.0
---

Commit Message Skill

步骤
1. 运行 git diff --staged
2. 按 Conventional Commits 规范生成 message
...

3.2`SkillFrontmatter` 解析逻辑

// 正则匹配 --- 块
Pattern FRONTMATTER = Pattern.compile(
    "^---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---\\s*\\r?\\n?",
    Pattern.MULTILINE);

public static Parsed parse(String markdown) {
    Matcher m = FRONTMATTER.matcher(markdown);
    if (!m.find()) return new Parsed(Map.of(), markdown);   // 无 frontmatter，全是 body
    // 按行解析 key: value（引号包裹的 value 自动去引号）
    // 不支持嵌套 / 列表 / 多行标量
}

public record Parsed(Map<String, String> meta, String body) {
    public String name()        { return meta.getOrDefault("name", ""); }
    public String description() { return meta.getOrDefault("description", ""); }
}

自动补齐：若用户只填了 name/description，没写 frontmatter，`SkillFrontmatter.prependFrontmatter()` 在保存时自动在文件头补入：

public static String prependFrontmatter(String name, String description, String body) {
    return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body;
}

---

4. 存储布局

storage-root/
├── groups/
│   └── {groupId}/
│       └── skills/
│           └── skl_abc123/          ← 平台库 Skill（source 有据可查）
│               ├── SKILL.md         ← 入口手册（frontmatter + markdown body）
│               └── scripts/         ← 可选附件（脚本、模板、参考文件等）
│                   └── format.py
│
└── workspaces/
    └── {agentId}/
        └── skills/
            ├── skl_abc123/          ← 从平台库 mirror 过来（覆盖写，保持最新）
            │   ├── SKILL.md
            │   └── scripts/format.py
            └── my-custom-skill/     ← 用户自建（非 skl_ 前缀，无 DB 行）
                └── SKILL.md

注意：Agent 工作区下还有 `.ma/skills/` 挂载点（联接 `workspaces/{agentId}/skills/`），LLM 在路径提示中看到的是 `.ma/skills/{skillId}/`，实际读写指向同一物理目录。

---

5. Skill 安装来源

5.1 GitHub 安装 (`SkillInstallService.installFromGithub`)

支持多种 spec 格式：

# 简写形式
owner/repo                           → 从仓库根取 SKILL.md
owner/repo@skill-name                → 从 skill-name/ 子目录取
owner/repo@skill-name#branch-or-sha → 指定分支

# HTTPS URL 形式
https://github.com/owner/repo
https://github.com/owner/repo/tree/main/path/to/skill

# SSH URL
git@github.com:owner/repo.git

拉取流程：

GithubRef.parse(spec)           ← 解析所有格式
  ↓
resolveActualRef()              ← GET /repos/{owner}/{repo}，处理 GitHub rename 重定向
  ↓
tryFetchDirectory(ref, path)    ← GET /contents/{path}?ref=branch（GitHub API）
  失败(404) ↓                    fallback: 尝试 skills/{skillName}/ 子路径
downloadDirectory()             ← 递归拉取，最多 32 文件，单文件 ≤ 2MiB
  ↓                               文件 API 用 Accept: application/vnd.github.raw（避免 raw.githubusercontent.com 防火墙拦截）
registerInstalled()             ← 读 frontmatter，入 SQLite，filePath 写入 DB

5.2 上传安装 (`installFromUpload`)

- .md 文件 → 直接写为 SKILL.md
- .zip 文件 → 解压到 skill 目录：
  - 自动识别 GitHub zip 常见的单层顶层目录前缀并去除
  - 防 zip 路径穿透（含 .. 的 entry 直接拒绝）
  - 最多 32 文件，单文件 ≤ 2MiB
- 最终均需 SKILL.md 存在，否则清理目录后报错
5.3 手动创建 (`SkillService.create`)

UI 直接编辑：source=manual，applyAndPersist() 写文件 + 入库。

---

6. Agent 工作区同步 (`AgentSkillWorkspaceSync`)

触发时机：Agent 创建/保存时（`DefaultAgentWorkspaceLifecycle`）

同步策略（`syncBoundSkillsToAgentWorkspace`）：

输入：agentId + skillIds（Agent 绑定的 skill id 列表）

Step 1：清理不再绑定的平台 skill 镜像
  遍历 workspaces/{agentId}/skills/ 一级子目录
  if (SkillCatalogPolicy.isPlatformCatalogSkillId(dirName)    ← 以 skl_ 开头
      && !want.contains(dirName))                              ← 且不在新绑定列表
      deleteRecursively(sub)                                   ← 删除镜像

Step 2：拷贝新绑定的平台 skill
  for (skillId in skillIds) {
      src = storage/groups/{groupId}/skills/{skillId}/         ← 平台库源
      dst = workspaces/{agentId}/skills/{skillId}/
      if (dst.exists) deleteRecursively(dst)                   ← 先删旧镜像
      copyTree(src, dst)                                       ← 覆盖写（保持最新版本）
  }

关键设计：
- 平台 skill（skl_*）会被覆盖写（保证版本一致）
- 用户自建目录（非 `skl_*`）永远不删除
- 源目录缺失则跳过并打 warn，不影响 Agent 启动
---

7. 运行时 Skill 加载（两阶段合并）

// ISkillsRuntimeBinding（接口） → ServiceBackedSkillRuntimeBindings → SkillService
public List<Skill> resolveRuntimeSkills(
    String groupId, String agentId,
    List<String> platformSkillIds,          // AgentDefinition.skillIds
    boolean autoDiscoverWorkspaceSkills     // AgentDefinition 开关
) {
    // ① 平台库：按 id 查 SQLite
    for (String id : platformSkillIds) {
        repo.findById(groupId, id).ifPresent(s -> byId.put(id, s));
    }
    // ② 自动发现：扫描 workspaces/{agentId}/skills/ 下非 skl_* 合法目录
    if (autoDiscoverWorkspaceSkills) {
        for (Skill s : listCustomSkillsFromAgentWorkspace(agentId)) {
            if (!byId.containsKey(s.getId())) byId.put(s.getId(), s);  // 平台库优先
        }
    }
    return List.copyOf(byId.values());
}

自建包识别规则（`listCustomSkillsFromAgentWorkspace`）：

// 目录名须通过 SkillCatalogPolicy.isAllowedFilesystemDraftSkillDirectoryName(dirName)
//   - 非 skl_ 开头
//   - 仅含 [a-zA-Z0-9._-]
//   - 不与保留名冲突（sessions、runtimes、tools、workspaces 等）
//   - 长度 ≤ 200
// SKILL.md 须含非空的 name 和 description（frontmatter 解析），否则跳过

---

8. LLM 可见工具组装

SkillExecutor.toToolSpecs() 把每个 enabled Skill 转为 LLM ToolSpec：

// ToolSpec 结构：
// name:   sanitizeName(skill.name)  → 非字母数字字符替换为 _ ，截断 64 字符
// desc:   "[skill] " + truncate(description, 200)   → 截断 200 字符 + 省略号
// schema: { type: object, properties: {} }           → 无输入参数

// 例：name="commit-message" → tool name="commit_message"
//     LLM 调用时传空参数即可触发

所有 skill tool + builtin tool + MCP tool 合并成最终的 tools 列表发给 LLM。

---

9. Progressive Disclosure（渐进式披露）三层

第 1 层（LLM tools 列表）
  commit_message: [skill] Generates conventional commits from git diff
      ↓  LLM 决定调用 commit_message()

第 2 层（SkillExecutor.execute()）
  ----- BEGIN SKILL.md -----
  # Commit Message Skill
  步骤 1: git diff --staged
  步骤 2: 用 scripts/format.py 格式化
  ...
  ----- END SKILL.md -----

  ----- BUNDLED RESOURCES (skills/skl_abc/) -----
    scripts/format.py  (1.2 KiB)
    templates/commit.tmpl  (0.3 KiB)
  使用 read_skill_file(skill="skl_abc", path=...) 可按需读取。
      ↓  LLM 按手册执行，需要附件时：

第 3 层（read_skill_file）
  read_skill_file(skill="skl_abc", path="scripts/format.py")
  → 返回文件内容（含 truncated/size_bytes 元信息）

若没有附件（bundled resources 为空）：

----- BUNDLED RESOURCES -----
(none — 本 skill 没有附件，请不要尝试调用 read_skill_file 猜测路径。...)

---

10. 脚本执行机制（重点）

10.1 AI 可以执行 skill 包内的脚本

这是设计上明确支持的。`SkillExecutor.execute()` 的返回文本里有显式路径提示：

shell_exec 的 cwd 即该工作区根；
运行上表脚本请用 skills/{skillId}/scripts/... 等相对路径；
task 落盘文件用显式 sessions/ses_*/...

所以 AI 可以：

# SKILL.md 手册指导 LLM 这样调用
shell_exec: python skills/skl_abc123/scripts/format.py --input=xxx
shell_exec: node  skills/skl_abc123/scripts/transform.js
shell_exec: python3 skills/skl_abc123/scripts/analyze.py

这些命令：
1. `shell_exec` 白名单检查：`python`/`node` 都在白名单 ✓
2. `WorkspacePolicyHook`：路径 `skills/...` 是相对路径，落在 workspace 内 ✓
3. **`ShellArgvSkillsMutationGuard`**：只拦截写操作，执行（读文件运行）不拦截 ✓

10.2`ShellArgvSkillsMutationGuard`：只读保护

// 被拦截的命令（MUTATING_ARGV0）：
"rm", "rmdir", "del", "erase", "rd",    // 删除
"mv", "move", "ren", "rename",           // 移动/改名
"cp", "copy", "xcopy", "robocopy",       // 复制写入
"touch", "truncate", "shred"             // 创建/清空

// 以及重定向写入：argv 中含 ">" 或 ">>" 且目标在 skills/ 下

被保护的路径：
- skills/ 及其子树（skills/skl_abc/...）
- .ma/skills/ 及其子树
- 遗留路径：skills-draft/、draft-skills/
允许的操作：
- `python skills/skl_abc/scripts/run.py` → 读文件执行，不写 skills/ → 允许
- cat skills/skl_abc/SKILL.md → 读取 → 允许（但推荐用 read_skill_file）
- git diff skills/ → 读取 → 允许
拒绝的操作：
- `rm skills/skl_abc/scripts/run.py` → 删除 → 拒绝
- `echo xxx > skills/skl_abc/SKILL.md` → 写入 → 拒绝
- `cp /tmp/evil.py skills/skl_abc/scripts/` → 复制写入 → 拒绝

10.3 总结：什么可以做，什么不可以

操作
是否允许
原因
shell_exec("python", "skills/xxx/scripts/run.py")
✅
仅读取并执行，非写操作
read_skill_file(skill, path="scripts/run.py")
✅
专用懒加载工具
shell_exec("cat", "skills/xxx/SKILL.md")
✅
读取，非写
shell_exec("python", "-c", "open('skills/xxx/x','w').write(...)")
✅（Python 层面） / 业务上不符合设计
Guard 仅检测 argv[0] 和明显写命令
shell_exec("rm", "skills/xxx/scripts/run.py")
❌
MUTATING_ARGV0 拦截
shell_exec("echo", ">", "skills/xxx/SKILL.md")
❌
重定向写 skills/
write_file("skills/xxx/new.py", content)
❌
WorkspacePolicyHook 拦截

---

11. ReadSkillFileTool 详解

工具名：read_skill_file，入参：{ skill: string, path: string }

// 路由 skill 参数（id 或 name 均接受）
Skill hit = resolveSkill(ctx.skills(), skillKey);
// id 精确匹配 → name 精确匹配 → sanitizeName 后匹配

// 安全校验
if ("SKILL.md".equalsIgnoreCase(cleaned)) return "ERROR: do not read SKILL.md via read_skill_file";
if (cleaned.contains("..")) return "ERROR: path must not contain '..'";
if (!file.startsWith(skillDir)) return "ERROR: path escapes skill directory";

// 路径解析（优先 agent 工作区镜像）
Path skillDir = workspaces/{agentId}/skills/{skillId}/  // 镜像优先
             ?: storage-root/skills/{skillId}/           // 回退全局

// 截断处理
if (raw.length > maxBytes) { truncated = true; body = first maxBytes bytes; }
// 返回：{ skill, path, size_bytes, truncated?, max_bytes?, body }

---

12. SkillCatalogPolicy：平台约定

// 平台 Skill id 前缀
String PLATFORM_SKILL_ID_PREFIX = "skl_";

// 以 skl_ 开头 = 平台库托管，DB 行权威
isPlatformCatalogSkillId("skl_abc") → true
isPlatformCatalogSkillId("my-tool") → false

// 允许的自建目录名：[a-zA-Z0-9._-]，≤200字符，非 skl_ 前缀，非保留名
isAllowedFilesystemDraftSkillDirectoryName("my-deploy-tool") → true
isAllowedFilesystemDraftSkillDirectoryName("sessions")       → false（保留名）
isAllowedFilesystemDraftSkillDirectoryName("skl_abc")        → false（平台前缀）

// Skills 目录对工具只读
isWriteForbiddenWorkspaceRelativePath("skills/skl_abc/SKILL.md") → true
isWriteForbiddenWorkspaceRelativePath("sessions/ses_xyz/out.txt") → false

---

13. 完整加载链路时序

用户在 Agent 设置页勾选 Skill → AgentDefinition.skillIds = ["skl_abc"]
         ↓ Agent 保存
AgentSkillWorkspaceSync.syncBoundSkillsToAgentWorkspace(groupId, agentId, ["skl_abc"])
  → groups/{groupId}/skills/skl_abc/ → copyTree → workspaces/{agentId}/skills/skl_abc/
         ↓ 推理时
ISkillsRuntimeBinding.resolveRuntimeSkills(groupId, agentId, ["skl_abc"], autoDiscover)
  → repo.findById(groupId, "skl_abc") → Skill 对象
  → 若 autoDiscover: 扫描 workspaces/{agentId}/skills/ 自建包
         ↓
SkillExecutor.toToolSpecs(skills)
  → LlmClient.ToolSpec("commit_message", "[skill] ...", {})
         ↓ LLM function call: commit_message()
SkillExecutor.execute(skill, argsJson, agent)
  → 读 workspaces/{agentId}/skills/skl_abc/SKILL.md
  → listBundledFiles() 枚举附件
  → 返回 SKILL.md 全文 + 附件清单 + 路径提示
         ↓ LLM 按手册执行，需附件时
ReadSkillFileTool.execute("skill=skl_abc, path=scripts/run.py")
  → 读文件 + 截断保护 → 返回文件内容
         ↓ LLM 决定执行脚本时
shell_exec("python", "skills/skl_abc/scripts/run.py")
  → ShellArgvSkillsMutationGuard.checkDenied() → null（非写操作，通过）
  → 实际执行 runtimes/pyvenv/Scripts/python.exe skills/skl_abc/scripts/run.py

---

14. 8 个难点

难点 1：Progressive Disclosure 的层级不能混用

SKILL.md 只能通过 skill 工具（function call）触发 `SkillExecutor.execute()` 返回，不能通过 `read_skill_file` 直接读：

if ("SKILL.md".equalsIgnoreCase(cleaned))
    return "ERROR: do not read SKILL.md via read_skill_file; call the skill tool instead";

因为 SKILL.md 通过 skill 工具返回时，会附加路径提示、附件清单、使用说明等上下文，直接读缺乏引导。

难点 2：自建包（workspace-skills）没有 DB 行

autoDiscoverWorkspaceSkills=true 时扫描到的自建 skill，Skill 对象的 `groupId` 为 null，`userId` 为 null，`filePath` 为相对 agent workspace 的路径（`skills/{dirName}/SKILL.md`），不在 SQLite 里。所以 `resolveRoute` 无法通过 id 在 DB 找到它——这是设计意图，自建包仅运行时内存态存在。

难点 3：AgentSkillWorkspaceSync 覆盖写而非补缺写

与 `AgentRuntimeWorkspaceSync`（node/codegraph 的"补缺不覆盖"）相反，skill 同步是先 deleteRecursively 再 copyTree，保证每次 Agent 保存后工作区镜像与平台库版本一致。这是为了让 Skill 更新能立即反映到 Agent。

难点 4：shell_exec 执行脚本时的路径解析

shell_exec 的 cwd 是 agent workspace 根（workspaces/{agentId}/），所以脚本路径：
- skills/skl_abc/scripts/run.py → cwd + 相对路径 → 正确
- runtimes/pyvenv/Scripts/python.exe → cwd + 相对路径 → 正确（但不需要手写，python 命令被 runtime-command-paths 自动替换）
不要写绝对路径（会被 WorkspacePolicyHook 拦截），不要写 workspaces/{agentId}/skills/...（从 cwd 出发就是 skills/...）。

难点 5：`ShellArgvSkillsMutationGuard` 无法防御 Python 内部 I/O

Guard 仅检测 shell_exec 的 argv，不能阻止：
# LLM 生成的 Python 代码里 open('skills/xxx/SKILL.md', 'w').write('...')
这是设计上的已知局限。防御靠文件工具（write_file 的 WorkspacePolicyHook），不靠 Guard。

难点 6：Skill name 作为工具名需 sanitize

sanitizeName("commit message") → "commit_message"
sanitizeName("my-deploy-tool") → "my-deploy-tool"
sanitizeName("数据库备份") → "____"

特殊字符（空格、中文、符号）全替换为 _，截断 64 字符。若两个 Skill sanitize 后 name 相同，LLM 看到的是同名工具——复现时要注意 name 的唯一性。

难点 7：GitHub 安装的双路径 fallback

owner/repo@skill-name 先尝试仓库根的 skill-name/ 路径，404 后自动 fallback 到 skills/skill-name/（适配 anthropics/skills 等多技能 repo 结构）。两次都找不到 SKILL.md 才报错。

难点 8：zip 解压的 "虚假目录项" 问题

某些打包工具把目录写成无尾随 / 的 0 字节文件 entry。extractZip 通过 inferDirectoryPrefixes() 预扫描所有 entry 路径、推断隐含目录，先创建好目录，避免后续在已存在同名文件上 Files.createDirectories() 报错（Windows 上表现为路径相关的奇怪异常）。

---

15. 复现检查清单（16 步）

存储与 domain
[] 1. Skill domain 对象：source 四种值 + filePath 相对路径格式
[] 2. SkillFrontmatter.parse()：frontmatter 正则 + key:value 解析 + 引号去除
[] 3. SkillFrontmatter.prependFrontmatter()：无 frontmatter 时自动补写
[] 4. SkillRepository SQLite CRUD（与 MCP 类似的 group_id 隔离）
安装
[] 5. SkillInstallService.GithubRef.parse()：7 种输入格式统一解析
[] 6. resolveActualRef()：GitHub rename 重定向处理
[] 7. tryFetchDirectory() + fallback skills/{name} 路径
[] 8. extractZip() 的 inferDirectoryPrefixes() 虚假目录项兜底
[] 9. installFromUpload() 的 zip/md 两种格式
工作区同步
[] 10. AgentSkillWorkspaceSync：skl_* 删旧 + 覆盖写（区别于 runtime 的补缺不覆盖）
[] 11. 自建目录（非 skl_*）永不删除原则
运行时加载
[] 12. resolveRuntimeSkills()：平台库 + autoDiscover 两阶段合并，平台库优先
[] 13. listCustomSkillsFromAgentWorkspace()：isAllowedFilesystemDraftSkillDirectoryName() 过滤规则
Progressive Disclosure
[] 14. SkillExecutor.execute()：SKILL.md 全文 + 附件清单 + 路径提示 + 无附件时的 none 消息
[] 15. ReadSkillFileTool：禁读 SKILL.md + .. 路径检查 + agent 工作区镜像优先
脚本执行与保护
[] 16. ShellArgvSkillsMutationGuard：MUTATING_ARGV0 列表 + 重定向检测 + targetsReadOnlySkillsTree 三层路径解析
---

16. 关键文件索引

文件
职责
harness/domain/Skill.java
Skill domain 对象
harness/skill/SkillFrontmatter.java
YAML frontmatter 解析 + 补写
harness/skill/SkillExecutor.java
execute() 返回 SKILL.md + toToolSpec()
harness/skill/SkillCatalogPolicy.java
skl_ 前缀约定 + 路径只读判断
harness/tool/impl/ReadSkillFileTool.java
read_skill_file 懒加载附件
harness/tool/shell/ShellArgvSkillsMutationGuard.java
shell_exec 写 skills/ 拦截
harness/workspace/AgentSkillWorkspaceSync.java
Agent 保存时 skill 镜像同步
harness/runtime/ISkillsRuntimeBinding.java
harness↔宿主防腐接口
memory-agent/service/SkillService.java
CRUD + resolveRuntimeSkills + 自建发现
memory-agent/service/SkillInstallService.java
GitHub 拉取 + zip 解压 + 入库
memory-agent/config/beans/ServiceBackedSkillRuntimeBindings.java
防腐接口实现