package com.example.travscraper;

import com.example.travscraper.service.HorseWarningService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TravScraperApplication implements CommandLineRunner {

    private final AtgScraperService service;
    private final HorseWarningService horseWarningService;

    public TravScraperApplication(AtgScraperService svc, HorseWarningService horseWarningService
    ) {
        this.service = svc;
        this.horseWarningService = horseWarningService;
    }

    public static void main(String[] args) {
        SpringApplication.run(TravScraperApplication.class, args);
    }

    @Override
    public void run(String... args) {
        //service.scrapeForeign();
        service.scrape();
        service.scrapeFuture();
        service.scrapeResultatPopupsOnly();
        horseWarningService.refreshWarnings(8);
    }
}
