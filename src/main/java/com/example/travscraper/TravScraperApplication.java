// src/main/java/com/example/travscraper/TravScraperApplication.java
package com.example.travscraper;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TravScraperApplication implements CommandLineRunner {

    private final AtgScraperService service;
    private final com.example.travscraper.Service.HorseWarningService horseWarningService;

    public TravScraperApplication(
            AtgScraperService svc,
            com.example.travscraper.Service.HorseWarningService horseWarningService
    ) {
        this.service = svc;
        this.horseWarningService = horseWarningService;
    }

    public static void main(String[] args) {
        SpringApplication.run(TravScraperApplication.class, args);
    }

    @Override
    public void run(String... args) {
        runResultatPopupScrape(); //Changed!
        horseWarningService.refreshWarnings(8);
        service.scrapeFuture();
        service.scrape();
        service.scrapeForeign();
    }

    private void runResultatPopupScrape() { //Changed!
        service.scrapeResultatPopupsOnly(); //Changed!
    } //Changed!
}
