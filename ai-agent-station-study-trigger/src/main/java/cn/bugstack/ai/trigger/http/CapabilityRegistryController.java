package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.trigger.service.capability.CapabilityRegistryService;
import jakarta.annotation.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilityRegistryController {

    @Resource private CapabilityRegistryService registry;

    @GetMapping("/mcps") public List<Map<String,Object>> mcps(){return registry.listMcps();}
    @GetMapping("/mcps/{serverId}/versions") public List<Map<String,Object>> mcpVersions(@PathVariable("serverId") long serverId){return registry.listMcpVersions(serverId);}
    @GetMapping("/mcp-bindings") public List<Map<String,Object>> releasedMcpBindings(){return registry.listReleasedMcpBindings();}
    @GetMapping("/mcp-versions/{id}") public Map<String,Object> mcpVersion(@PathVariable("id") long id){return registry.mcpVersionDetail(id);}

    @PostMapping("/mcps") public Map<String,Object> registerMcp(@RequestBody Map<String,Object> body,
            @RequestHeader(value="X-Actor",defaultValue="local-developer")String actor,
            @RequestHeader(value="X-Role",defaultValue="DEVELOPER")String role){requireRole(role,"DEVELOPER");return Map.of("success",true,"id",registry.registerMcp(body,actor));}

    @PostMapping("/mcps/local-test")
    public Map<String,Object> seedLocalTestMcp() {
        return Map.of("success", true, "versionId", registry.seedLocalTestMcp(), "message", "本地测试 MCP 已创建");
    }

    @PostMapping("/mcps/{serverId}/versions") public Map<String,Object> createMcpVersion(@PathVariable("serverId") long serverId,@RequestBody Map<String,Object> body,
            @RequestHeader(value="X-Actor",defaultValue="local-developer")String actor,@RequestHeader(value="X-Role",defaultValue="DEVELOPER")String role){requireRole(role,"DEVELOPER");return Map.of("success",true,"id",registry.createMcpVersion(serverId,body,actor));}

    @PostMapping("/mcp-versions/{id}/connectivity-test") public Map<String,Object> connectivity(@PathVariable("id") long id,
            @RequestHeader(value="X-Actor",defaultValue="local-tester")String actor,@RequestHeader(value="X-Role",defaultValue="TESTER")String role){requireRole(role,"TESTER");return registry.testMcpConnectivity(id,actor);}

    @PostMapping("/mcp-versions/{id}/discovery") public Map<String,Object> discovery(@PathVariable("id") long id,@RequestBody Map<String,List<Map<String,Object>>> body,
            @RequestHeader(value="X-Actor",defaultValue="local-tester")String actor,@RequestHeader(value="X-Role",defaultValue="TESTER")String role){requireRole(role,"TESTER");registry.recordMcpDiscovery(id,body.get("tools"),actor);return Map.of("success",true);}

    @PostMapping("/mcp-versions/{id}/security-scan") public Map<String,Object> scanMcp(@PathVariable("id") long id,
            @RequestHeader(value="X-Actor",defaultValue="local-security")String actor,@RequestHeader(value="X-Role",defaultValue="SECURITY_REVIEWER")String role){requireRole(role,"SECURITY_REVIEWER");registry.scanMcp(id,actor);return Map.of("success",true);}

    @PostMapping("/mcp-versions/{id}/sandbox-test") public Map<String,Object> testMcp(@PathVariable("id") long id,@RequestBody(required=false) Map<String,Object> report,
            @RequestHeader(value="X-Actor",defaultValue="local-tester")String actor,@RequestHeader(value="X-Role",defaultValue="TESTER")String role){requireRole(role,"TESTER");registry.completeMcpTests(id,actor,report);return Map.of("success",true);}

    @PostMapping("/mcp-versions/{id}/submit-review") public Map<String,Object> submitMcp(@PathVariable("id") long id,
            @RequestHeader(value="X-Actor",defaultValue="local-developer")String actor,@RequestHeader(value="X-Role",defaultValue="DEVELOPER")String role){requireRole(role,"DEVELOPER");registry.submitMcpReview(id,actor);return Map.of("success",true);}

    @PostMapping("/mcp-versions/{id}/reviews") public Map<String,Object> reviewMcp(@PathVariable("id") long id,@RequestBody Map<String,Object> body,
            @RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){String type=String.valueOf(body.get("reviewType")).toUpperCase();requireRole(role,type+"_REVIEWER");registry.reviewMcp(id,type,String.valueOf(body.getOrDefault("decision","APPROVED")),actor,String.valueOf(body.getOrDefault("comment","")));return Map.of("success",true);}

    @PostMapping("/mcp-versions/{id}/releases") public Map<String,Object> releaseMcp(@PathVariable("id") long id,@RequestBody Map<String,Object> body,
            @RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){requireRole(role,"RELEASE_MANAGER");long releaseId=registry.releaseMcp(id,String.valueOf(body.getOrDefault("environment","DEV")),body.get("rolloutPercent") instanceof Number n?n.intValue():100,actor);return Map.of("success",true,"releaseId",releaseId);}

    @PostMapping("/agents/{agentId}/mcp-bindings") public Map<String,Object> bindMcp(@PathVariable("agentId") String agentId,@RequestBody Map<String,Object> body,
            @RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){requireRole(role,"AGENT_ADMIN");long releaseId=((Number)body.get("releaseId")).longValue();@SuppressWarnings("unchecked") List<String> tools=(List<String>)body.getOrDefault("toolAllowlist",List.of());registry.bindMcp(agentId,releaseId,actor,tools);return Map.of("success",true);}

    @GetMapping("/skills") public List<Map<String,Object>> skills(){return registry.listSkills();}
    @GetMapping("/skills/{packageId}/versions") public List<Map<String,Object>> skillVersions(@PathVariable("packageId") long packageId){return registry.listSkillVersions(packageId);}
    @GetMapping("/skill-versions/{id}") public Map<String,Object> skillVersion(@PathVariable("id") long id){return registry.skillVersionDetail(id);}

    @PostMapping(value="/skills/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Map<String,Object> uploadSkill(
            @RequestParam("skillKey") String skillKey,@RequestParam("name") String name,@RequestParam(value="description",defaultValue="")String description,@RequestParam("version") String version,@RequestPart("file")MultipartFile file,
            @RequestHeader(value="X-Actor",defaultValue="local-developer")String actor,@RequestHeader(value="X-Role",defaultValue="DEVELOPER")String role)throws Exception{requireRole(role,"DEVELOPER");return Map.of("success",true,"versionId",registry.uploadSkill(skillKey,name,description,version,file,actor));}

    @PostMapping("/skill-versions/{id}/security-scan") public Map<String,Object> scanSkill(@PathVariable("id") long id,@RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role)throws Exception{requireRole(role,"SECURITY_REVIEWER");registry.scanSkill(id,actor);return Map.of("success",true);}
    @PostMapping("/skill-versions/{id}/sandbox-test") public Map<String,Object> testSkill(@PathVariable("id") long id,@RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role)throws Exception{requireRole(role,"TESTER");registry.testSkill(id,actor);return Map.of("success",true);}
    @PostMapping("/skill-versions/{id}/submit-review") public Map<String,Object> submitSkill(@PathVariable("id") long id,@RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){requireRole(role,"DEVELOPER");registry.submitSkillReview(id,actor);return Map.of("success",true);}
    @PostMapping("/skill-versions/{id}/reviews") public Map<String,Object> reviewSkill(@PathVariable("id") long id,@RequestBody Map<String,Object> body,@RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){String type=String.valueOf(body.get("reviewType")).toUpperCase();requireRole(role,type+"_REVIEWER");registry.reviewSkill(id,type,String.valueOf(body.getOrDefault("decision","APPROVED")),actor,String.valueOf(body.getOrDefault("comment","")));return Map.of("success",true);}
    @PostMapping("/skill-versions/{id}/sign") public Map<String,Object> signSkill(@PathVariable("id") long id,@RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){requireRole(role,"RELEASE_MANAGER");registry.signSkill(id,actor);return Map.of("success",true);}
    @PostMapping("/skill-versions/{id}/releases") public Map<String,Object> releaseSkill(@PathVariable("id") long id,@RequestBody Map<String,Object> body,@RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){requireRole(role,"RELEASE_MANAGER");long releaseId=registry.releaseSkill(id,String.valueOf(body.getOrDefault("environment","DEV")),body.get("rolloutPercent") instanceof Number n?n.intValue():100,actor);return Map.of("success",true,"releaseId",releaseId);}
    @PostMapping("/agents/{agentId}/skill-bindings") public Map<String,Object> bindSkill(@PathVariable("agentId") String agentId,@RequestBody Map<String,Object> body,@RequestHeader("X-Actor")String actor,@RequestHeader("X-Role")String role){requireRole(role,"AGENT_ADMIN");long releaseId=((Number)body.get("releaseId")).longValue();@SuppressWarnings("unchecked")Map<String,Object> override=(Map<String,Object>)body.getOrDefault("configOverride",Map.of());registry.bindSkill(agentId,releaseId,actor,override);return Map.of("success",true);}

    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    public ResponseEntity<Map<String,Object>> badRequest(RuntimeException error){return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("success",false,"message",error.getMessage()));}

    private void requireRole(String actual,String expected){if(!expected.equalsIgnoreCase(actual))throw new IllegalStateException("Role "+expected+" is required");}
}
