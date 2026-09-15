package com.aitest.workbench;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AssetMetricsMapper {
    @Select("SELECT asset_type AS type,COUNT(*) AS total FROM asset WHERE project_id=#{projectId} AND deleted=FALSE GROUP BY asset_type")
    List<Map<String, Object>> counts(String projectId);
}
