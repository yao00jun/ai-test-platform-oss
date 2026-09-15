package com.aitest.notification;

import com.aitest.common.JsonCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookProtocolTest {
    private final WebhookProtocol protocol = new WebhookProtocol(new JsonCodec());
    @Test void explicitCodesAndConflictingFeishuCodesCannotBecomeFalseSuccess() {
        assertThat(protocol.response("FEISHU", 200, "{\"code\":0,\"StatusCode\":1}").outcome()).isEqualTo("UNCERTAIN");
        assertThat(protocol.response("FEISHU", 200, "{\"StatusCode\":0}").outcome()).isEqualTo("DELIVERED");
        assertThat(protocol.response("DINGTALK", 200, "{\"errcode\":1}").outcome()).isEqualTo("REJECTED");
        assertThat(protocol.response("WECHAT_WORK", 200, "{\"errcode\":\"0\"}").outcome()).isEqualTo("UNCERTAIN");
        assertThat(protocol.response("DINGTALK", 401, "{\"errcode\":0}").outcome()).isEqualTo("REJECTED");
        assertThat(protocol.response("FEISHU", 204, "").outcome()).isEqualTo("UNCERTAIN");
        assertThat(protocol.response("DINGTALK", 200, "null").outcome()).isEqualTo("UNCERTAIN");
        assertThat(protocol.response("FEISHU", 200, "{\"code\":null,\"StatusCode\":0}").outcome()).isEqualTo("UNCERTAIN");
    }
}
