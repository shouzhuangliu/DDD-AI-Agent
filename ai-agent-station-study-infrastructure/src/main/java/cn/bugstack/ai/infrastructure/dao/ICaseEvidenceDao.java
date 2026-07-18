package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.CaseEvidence;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ICaseEvidenceDao { int insertIgnore(CaseEvidence evidence); }
