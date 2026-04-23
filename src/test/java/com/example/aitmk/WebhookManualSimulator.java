package com.example.aitmk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 手动发送 WhatsApp webhook 模拟消息（包含 referral 广告信息）：
 *
 * <pre>
 * mvn -q -DskipTests test-compile exec:java \
 *   -Dexec.mainClass=com.example.aitmk.WebhookManualSimulator \
 *   -Dexec.args="http://localhost:6153/webhook 6288880000111 1234567890 ad 2 500"
 * </pre>
 *
 * 参数：
 * 1) webhook 地址（默认 http://localhost:6153/webhook）
 * 2) 客户手机号 from（默认 6288880000111）
 * 3) business phone_number_id（默认 1019964791197772）
 * 4) 模式 ad/plain（默认 ad，ad 模式会带 referral 广告信息）
 * 5) 连续发送条数（默认 1）
 * 6) 每条间隔毫秒（默认 300）
 */
public class WebhookManualSimulator {

    public static void main(String[] args) throws Exception {
        String webhookUrl = args.length > 0 ? args[0] : "http://localhost:6153/webhook";
        String from = args.length > 1 ? args[1] : "6288880000111";
        String phoneNumberId = args.length > 2 ? args[2] : "1019964791197772";
        String mode = args.length > 3 ? args[3] : "ad";
        int count = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        long sleepMillis = args.length > 5 ? Long.parseLong(args[5]) : 300L;

        HttpClient client = HttpClient.newHttpClient();
        boolean withReferral = !"plain".equalsIgnoreCase(mode);
        for (int i = 1; i <= Math.max(1, count); i++) {
            String payload = buildPayload(from, phoneNumberId, withReferral, i);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            System.out.printf("POST %s [#%d] status=%d, body=%s%n", webhookUrl, i, response.statusCode(), response.body());
            System.out.println("Payload:");
            System.out.println(payload);

            if (i < count) {
                Thread.sleep(Math.max(0L, sleepMillis));
            }
        }
    }

    private static String buildPayload(String from, String phoneNumberId, boolean withReferral, int index) {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String msgId = "wamid.manual." + ts + "." + index;
        String textBody = index == 1 ? "I want to learn more" : "follow-up message " + index;
        String referralJson = withReferral
                ? """
                                ,"referral": {
                                  "source_url": "https://example.com/ad",
                                  "source_id": "ad_123456",
                                  "source_type": "ad",
                                  "body": "Ad primary text demo",
                                  "headline": "Ad headline demo",
                                  "media_type": "image",
                                  "image_url": "https://example.com/ad-image.jpg",
                                  "video_url": "https://example.com/ad-video.mp4",
                                  "thumbnail_url": "https://example.com/ad-video-thumb.jpg",
                                  "ctwa_clid": "ctwa_click_id_demo",
                                  "welcome_message": {
                                    "text": "Hi, I came from your ad"
                                  }
                                }
                        """
                : "";
        return """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "WABA_ID_DEMO",
                      "changes": [
                        {
                          "field": "messages",
                          "value": {
                            "messaging_product": "whatsapp",
                            "metadata": {
                              "display_phone_number": "15551234567",
                              "phone_number_id": "%s"
                            },
                            "contacts": [
                              {
                                "profile": {
                                  "name": "Ad Lead Demo"
                                },
                                "wa_id": "%s"
                              }
                            ],
                            "messages": [
                              {
                                "from": "%s",
                                "id": "%s",
                                "timestamp": "%s",
                                "type": "text",
                                "text": {
                                  "body": "%s"
                                }%s
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(phoneNumberId, from, from, msgId, ts, textBody, referralJson);
    }
}
