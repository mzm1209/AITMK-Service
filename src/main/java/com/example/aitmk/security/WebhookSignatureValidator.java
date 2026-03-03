package com.example.aitmk.security;

import com.example.aitmk.config.WhatsAppConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
public class WebhookSignatureValidator {

    private final WhatsAppConfig properties;

    public void validate(String signature, String payload) {

        if (signature == null || !signature.startsWith("sha256=")) {
            throw new RuntimeException("Missing signature header");
        }

        try {
            String expected = signature.substring(7);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getAppSecret().getBytes(),
                    "HmacSHA256"));

            byte[] digest = mac.doFinal(payload.getBytes());
            String actual = HexFormat.of().formatHex(digest);

            if (!actual.equals(expected)) {
                throw new RuntimeException("Invalid webhook signature");
            }

        } catch (Exception e) {
            throw new RuntimeException("Signature validation failed", e);
        }
    }
}
