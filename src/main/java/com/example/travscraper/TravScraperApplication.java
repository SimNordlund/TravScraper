package com.example.travscraper;

import com.example.travscraper.service.DoubleGangerService;
import com.example.travscraper.service.HorseWarningService;
import com.example.travscraper.service.ReducedScraperService;
import com.example.travscraper.service.ReducedTrioScraping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

@SpringBootApplication
@Slf4j
public class TravScraperApplication implements ApplicationRunner {

    private static final String SCRAPER_JOB_OPTION = "scraper.job";
    private static final String DAILY_JOB = "daily";

    private final AtgScraperService service;
    private final HorseWarningService horseWarningService;
    private final DoubleGangerService doubleGangerService;
    private final ReducedScraperService reducedScraperService;
    private final ReducedTrioScraping reducedTrioScraping;

    public TravScraperApplication(AtgScraperService service, HorseWarningService horseWarningService, DoubleGangerService doubleGangerService, ReducedScraperService reducedScraperService, ReducedTrioScraping reducedTrioScraping) {
        this.service = service;
        this.horseWarningService = horseWarningService;
        this.doubleGangerService = doubleGangerService;
        this.reducedScraperService = reducedScraperService;
        this.reducedTrioScraping = reducedTrioScraping;
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(TravScraperApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        ConfigurableApplicationContext context = null;
        int exitCode = 1;
        try {
            context = app.run(args);
            exitCode = SpringApplication.exit(context);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.exit(exitCode);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        String job = scraperJob(args);
        if (!DAILY_JOB.equalsIgnoreCase(job)) {
            throw new IllegalArgumentException("Unsupported scraper job '" + job + "'. Use --scraper.job=daily.");
        }

        log.info("Starting scraper job '{}'", DAILY_JOB);
        runDailyJob();
        log.info("Finished scraper job '{}'", DAILY_JOB);
    }

    private String scraperJob(ApplicationArguments args) {
        if (!args.containsOption(SCRAPER_JOB_OPTION)) {
            return "";
        }

        List<String> values = args.getOptionValues(SCRAPER_JOB_OPTION);
        if (values == null) {
            return "";
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((first, second) -> second)
                .orElse("");
    }

    private void runDailyJob() {
        //doubleGangerService.refreshDoubleGangers();
        runStep("reduced games", reducedScraperService::scrapeAllReducedGames);
        runStep("reduced trio", reducedTrioScraping::scrapeTrio);
        runStep("future starts", service::scrapeFuture);
        runStep("result popups", service::scrapeResultatPopupsOnly);
        runStep("results", service::scrape);
        runStep("foreign results", service::scrapeForeign);
        runStep("horse warnings", () -> horseWarningService.refreshWarnings(8));
    }

    private void runStep(String name, ScraperStep step) {
        try {
            log.info("Running scraper step '{}'", name);
            step.run();
        } catch (Exception e) {
            throw new IllegalStateException("Scraper step failed: " + name, e);
        }
    }

    @FunctionalInterface
    private interface ScraperStep {
        void run();
    }
}
