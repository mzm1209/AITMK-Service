package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Exception;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class CursorCodecTest {
    @Test void v2RoundTripPreservesEpochMicrosExactly() {
        Instant at=Instant.parse("2026-06-22T01:25:08.227286Z");
        String cursor=CursorCodec.encode(at,42);
        CursorCodec.Key decoded=CursorCodec.decode(cursor);
        assertThat(decoded.at()).isEqualTo(at);
        assertThat(decoded.at().getNano()).isEqualTo(227_286_000);
        assertThat(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8)).startsWith("v2:");
    }

    @Test void legacyMillisCursorRemainsReadable() {
        String old=Base64.getUrlEncoder().withoutPadding().encodeToString(
                "1782091508227:9".getBytes(StandardCharsets.UTF_8));
        assertThat(CursorCodec.decode(old)).isEqualTo(new CursorCodec.Key(Instant.ofEpochMilli(1782091508227L),9));
    }

    @Test void invalidBase64VersionAndNumbersReturnSafeCursorError() {
        assertInvalid("not-base64!");
        assertInvalid(encoded("v3:123:1"));
        assertInvalid(encoded("v2:not-number:1"));
        assertInvalid(encoded("v2:123:not-number"));
    }

    private void assertInvalid(String value){assertThatThrownBy(()->CursorCodec.decode(value)).isInstanceOfSatisfying(V2Exception.class,
            ex->{assertThat(ex.getCode()).isEqualTo("CURSOR_INVALID");assertThat(ex.getMessage()).isEqualTo("游标无效或已过期");});}
    private String encoded(String raw){return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));}
}
