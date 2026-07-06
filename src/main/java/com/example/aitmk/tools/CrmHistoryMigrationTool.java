package com.example.aitmk.tools;

import com.example.aitmk.AitmkApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@Slf4j
public class CrmHistoryMigrationTool {
    public static void main(String[] args) {
        CrmHistoryMigrationOptions options = CrmHistoryMigrationOptions.parse(args);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AitmkApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "integration.schedulers-enabled=false",
                        "spring.main.lazy-initialization=false")
                .run(args)) {
            CrmHistoryMigrationService service = context.getBean(CrmHistoryMigrationService.class);
            CrmHistoryMigrationReport report = service.migrate(options);
            log.info(report.summary(options.dryRun()));
        }
    }
}
