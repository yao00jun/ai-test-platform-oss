package com.aitest.exchange.render;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Disposable process. Only platform-generated, escaped HTML reaches this entry point. */
public final class PdfRenderWorker {
    private PdfRenderWorker() { }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setTimeout(20000));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false).setOffline(true))) {
            context.route("**/*", Route::abort);
            Page page = context.newPage(); page.setDefaultTimeout(15000);
            page.setContent(Files.readString(directory.resolve("document.html"), StandardCharsets.UTF_8), new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(15000));
            page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
            page.pdf(new Page.PdfOptions().setPath(directory.resolve("document.pdf")).setFormat("A4").setPreferCSSPageSize(true).setPrintBackground(true).setDisplayHeaderFooter(true)
                    .setHeaderTemplate("<span></span>").setFooterTemplate("<div style='font-size:9px;width:100%;text-align:center;color:#718096'>AI-Test-Platform <span class='pageNumber'></span> / <span class='totalPages'></span></div>"));
        }
    }
}
