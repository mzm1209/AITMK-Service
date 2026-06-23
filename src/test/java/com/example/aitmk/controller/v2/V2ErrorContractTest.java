package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.*;
import com.example.aitmk.service.v2.ConversationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class V2ErrorContractTest {
    private final V2ExceptionHandler handler = new V2ExceptionHandler();

    @Test void forbiddenConflictAndExpiredCursorUseStandardEnvelope() {
        assertFailure(HttpStatus.FORBIDDEN,"FORBIDDEN");
        assertFailure(HttpStatus.CONFLICT,"VERSION_CONFLICT");
        assertFailure(HttpStatus.GONE,"EVENT_CURSOR_EXPIRED");
    }

    @Test void invalidCursorDoesNotLeakDecoderException() {
        var service=new ConversationQueryService(null,null,null,null,null,null);
        Throwable thrown=catchThrowable(()->ReflectionTestUtils.invokeMethod(service,"decode","not-base64!"));
        assertThat(thrown).isInstanceOf(V2Exception.class);
        V2Exception error=(V2Exception)thrown;
        assertThat(error.getCode()).isEqualTo("CURSOR_INVALID");
        assertThat(error.getMessage()).isEqualTo("游标无效或已过期");
        assertThat(error.getMessage()).doesNotContain("Base64","NumberFormat");
    }

    private void assertFailure(HttpStatus status,String code){var response=handler.business(new V2Exception(status,code,"message"));assertThat(response.getStatusCode()).isEqualTo(status);assertThat(response.getBody()).isNotNull();assertThat(response.getBody().success()).isFalse();assertThat(response.getBody().error().code()).isEqualTo(code);}
}
