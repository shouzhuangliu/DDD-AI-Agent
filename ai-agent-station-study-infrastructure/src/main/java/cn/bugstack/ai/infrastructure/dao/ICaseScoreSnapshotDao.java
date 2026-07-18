package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.CaseScoreSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ICaseScoreSnapshotDao { int insert(CaseScoreSnapshot snapshot); }
