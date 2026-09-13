package com.icecode.workbench.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void createsSuccessResponse() {
        ApiResponse<String> response = ApiResponse.success("ok");

        assertThat(response.getCode()).isZero();
        assertThat(response.getMessage()).isEqualTo("ok");
        assertThat(response.getData()).isEqualTo("ok");
    }

    @Test
    void createsFailureResponseWithoutData() {
        ApiResponse<Void> response = ApiResponse.failure(ErrorCode.NOT_INITIALIZED);

        assertThat(response.getCode()).isEqualTo(2001);
        assertThat(response.getMessage()).isEqualTo("工作台尚未完成首次配置");
        assertThat(response.getData()).isNull();
    }
}
