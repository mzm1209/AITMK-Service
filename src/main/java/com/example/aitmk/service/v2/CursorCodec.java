package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Exception;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** v2 时间/id 复合游标；输出微秒格式，同时兼容读取旧毫秒格式。 */
public final class CursorCodec {
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private CursorCodec() {}

    public static String encode(Instant at, long id) {
        if (at == null || id < 0) throw invalid();
        long epochMicros;
        try {
            epochMicros = Math.addExact(Math.multiplyExact(at.getEpochSecond(), MICROS_PER_SECOND), at.getNano() / 1_000L);
        } catch (ArithmeticException ex) {
            throw invalid();
        }
        String raw = "v2:" + epochMicros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Key decode(String cursor) {
        try {
            if (cursor == null || cursor.isBlank()) throw new IllegalArgumentException();
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length == 3) {
                if (!"v2".equals(parts[0])) throw new IllegalArgumentException();
                long micros = Long.parseLong(parts[1]);
                long seconds = Math.floorDiv(micros, MICROS_PER_SECOND);
                long microAdjustment = Math.floorMod(micros, MICROS_PER_SECOND);
                return new Key(Instant.ofEpochSecond(seconds, microAdjustment * 1_000L), parseId(parts[2]));
            }
            if (parts.length == 2) {
                // 兼容历史 Base64URL("<epochMillis>:<id>")，不再生成该格式。
                return new Key(Instant.ofEpochMilli(Long.parseLong(parts[0])), parseId(parts[1]));
            }
            throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            if (ex instanceof V2Exception v2) throw v2;
            throw invalid();
        }
    }

    private static long parseId(String raw) {
        long id = Long.parseLong(raw);
        if (id < 0) throw new IllegalArgumentException();
        return id;
    }

    private static V2Exception invalid() {
        return new V2Exception(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "游标无效或已过期");
    }

    public record Key(Instant at, long id) {}
}
