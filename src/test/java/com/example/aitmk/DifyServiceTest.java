package com.example.aitmk;

import com.example.aitmk.config.DifyConfig;
import com.example.aitmk.service.impl.DifyAiServiceImpl;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;

public class DifyServiceTest {

    public static void main(String[] args) {
        // 创建 Spring 容器
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TestConfig.class);
        context.refresh();

        DifyConfig config = new DifyConfig();
        config.setBaseUrl("https://api.dify.ai");
        config.setApiKey("app-OtSFboBn7jjg4Q3HCrGAjWdE");

        // 获取 DifyAiServiceImpl Bean
        DifyAiServiceImpl difyService = new DifyAiServiceImpl(config);

        // 测试输入
        String testQuery = "Hello, this is a test message from local debug!";

        try {
            String response = difyService.chat(testQuery);
            System.out.println("Dify API Response: " + response);
        } catch (Exception e) {
            System.err.println("调用 chat 方法出错：");
            e.printStackTrace();
        } finally {
            context.close();
        }
    }

    @ComponentScan(basePackages = "com.example.aitmk") // 扫描你的 service 包
    public static class TestConfig {
    }
}