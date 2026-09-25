package com.aitest.analysis;

import com.aitest.analysis.ast.MapperXmlParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MapperDynamicSqlTest {
    private final MapperXmlParser parser = new MapperXmlParser();

    @Test void ordinaryDynamicSqlIsRenderedAndOnlyNotedNotCountedAsAGap() {
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        var statements = parser.parse("PositionMapper.xml", """
                <mapper namespace="com.demo.PositionMapper">
                  <sql id="Base_Column_List">id, name, status</sql>
                  <select id="page">
                    SELECT <include refid="Base_Column_List"/> FROM t_position
                    <where>
                      <if test="name != null">AND name LIKE #{name}</if>
                      <if test="status != null">AND status = #{status}</if>
                    </where>
                  </select>
                  <update id="rename">
                    UPDATE t_position <set><if test="name != null">name = #{name},</if></set> WHERE id IN
                    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </update>
                  <insert id="add">INSERT INTO t_position <trim prefix="(" suffix=")" suffixOverrides=",">name,<if test="status != null">status,</if></trim> VALUES (#{name}, #{status})</insert>
                </mapper>""", diagnostics);
        assertThat(statements).extracting(row -> row.get("sql")).containsExactly(
                "SELECT id, name, status FROM t_position WHERE name LIKE #{name} AND status = #{status}",
                "UPDATE t_position SET name = #{name} WHERE id IN (#{id})",
                "INSERT INTO t_position ( name,status ) VALUES (#{name}, #{status})");
        assertThat(statements).extracting(row -> row.get("dynamic")).containsExactly(true, true, true);
        assertThat(diagnostics).isNotEmpty().allSatisfy(diagnostic -> {
            assertThat(diagnostic.severity()).as("MyBatis conditions are normal, not a gap").isEqualTo("INFO");
            assertThat(diagnostic.code()).isEqualTo("DYNAMIC_MAPPER_SQL");
        });
    }

    @Test void aStaticStatementThatOnlyReusesAFragmentIsNotDynamic() {
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        var statements = parser.parse("UserMapper.xml", """
                <mapper namespace="com.demo.UserMapper">
                  <sql id="cols">id, name</sql>
                  <select id="all">SELECT <include refid="com.demo.UserMapper.cols"/> FROM t_user</select>
                </mapper>""", diagnostics);
        assertThat(statements.getFirst()).containsEntry("sql", "SELECT id, name FROM t_user").containsEntry("dynamic", false);
        assertThat(diagnostics).isEmpty();
    }

    @Test void stringSplicingAndMissingFragmentsRemainVisibleGaps() {
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        parser.parse("ReportMapper.xml", """
                <mapper namespace="com.demo.ReportMapper">
                  <select id="sorted">SELECT id FROM t_report ORDER BY ${orderBy}</select>
                  <select id="elsewhere">SELECT <include refid="com.demo.Missing.cols"/> FROM t_report</select>
                </mapper>""", diagnostics);
        assertThat(diagnostics).extracting(SourceDiagnostic::code, SourceDiagnostic::severity)
                .containsExactly(tuple("MAPPER_STRING_SUBSTITUTION", "WARNING"), tuple("MAPPER_INCLUDE_UNRESOLVED", "WARNING"));
    }

    @Test void fragmentsFromAnotherMapperOfTheSameCodeBaseAreExpanded() {
        var fragments = new MapperXmlParser.Fragments();
        fragments.collect("<mapper namespace=\"com.demo.BaseMapper\"><sql id=\"Base_Column_List\">id, code</sql></mapper>");
        fragments.collect("<configuration/>");
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        var statements = parser.parse("ExtMapper.xml", """
                <mapper namespace="com.demo.ExtMapper">
                  <select id="codes">SELECT <include refid="com.demo.BaseMapper.Base_Column_List"/> FROM t_code</select>
                </mapper>""", diagnostics, fragments);
        assertThat(statements.getFirst()).containsEntry("sql", "SELECT id, code FROM t_code").containsEntry("unresolvedIncludes", List.of());
        assertThat(diagnostics).isEmpty();
    }
}
