package com.aitest.ai.text;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MdUtilCompatibilityTest {
    @Test
    void combinedStepsAndExpectedHeadingProducesStepsInsteadOfDroppingTheTable() {
        String markdown = """
                前言不应当生成一条用例
                featureCaseStart
                ## 登录成功
                ### 前置条件
                账号已注册
                ### 测试步骤与预期结果
                | 步骤 | 预期结果 |
                | --- | --- |
                | 输入 ${username}<br>点击登录 | 显示欢迎信息 |
                ### 备注
                P0
                featureCaseEnd
                """;
        var cases = MdUtil.batchTransformToCaseDTO(markdown);
        assertThat(cases).hasSize(1);
        assertThat(cases.getFirst().getName()).isEqualTo("登录成功");
        assertThat(cases.getFirst().getSteps()).hasSize(1);
        assertThat(cases.getFirst().getSteps().getFirst().getDesc()).isEqualTo("输入 ${username}\n点击登录");
        assertThat(cases.getFirst().getSteps().getFirst().getResult()).isEqualTo("显示欢迎信息");
    }
    @Test
    void truncatedOrNestedCaseBlocksAreRejectedInsteadOfCreatingPartialAssets() {
        assertThatThrownBy(() -> MdUtil.batchTransformToCaseDTO("featureCaseStart\n## 不完整")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> MdUtil.batchTransformToCaseDTO("featureCaseStart\nfeatureCaseStart\n## 嵌套\nfeatureCaseEnd")).isInstanceOf(RuntimeException.class);
    }
    @Test
    void cleaningHeadingsPreservesBoundariesVariablesAndBusinessPunctuation() {
        assertThat(TextCleaner.cleanMdTitle("##标题\nfeatureCaseEnd\nfeatureCaseStart\n##标题二"))
                .isEqualTo("## 标题\nfeatureCaseEnd\nfeatureCaseStart\n## 标题二");
        assertThat(TextCleaner.normalizeDocument("金额 ≥ 100，SQL: x='${token}'\r\n第二行"))
                .isEqualTo("金额 ≥ 100，SQL: x='${token}'\n第二行");
    }
}
