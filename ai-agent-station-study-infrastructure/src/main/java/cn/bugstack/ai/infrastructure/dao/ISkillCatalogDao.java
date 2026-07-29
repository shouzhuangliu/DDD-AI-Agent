package cn.bugstack.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface ISkillCatalogDao {
    List<Map<String, Object>> queryActiveReleasedSkills();
}
