package com.aitest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiTestApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--playwright-cli")) {
            com.microsoft.playwright.CLI.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length == 2 && args[0].equals("--browser-worker")) {
            try (var guard = com.aitest.common.WorkerOwnerGuard.watch()) {
                com.aitest.engine.web.PlaywrightWorker.main(new String[]{args[1]});
            }
            return;
        }
        if (args.length == 2 && args[0].equals("--pdf-worker")) {
            try (var guard = com.aitest.common.WorkerOwnerGuard.watch()) {
                com.aitest.exchange.render.PdfRenderWorker.main(new String[]{args[1]});
            }
            return;
        }
        SpringApplication.run(AiTestApplication.class, args);
    }
}
