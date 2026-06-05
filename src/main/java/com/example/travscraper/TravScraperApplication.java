package com.example.travscraper;

import com.example.travscraper.service.DoubleGangerService;
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
    private final DoubleGangerService doubleGangerService;

    public TravScraperApplication(
            AtgScraperService service,
            HorseWarningService horseWarningService,
            DoubleGangerService doubleGangerService
    ) {
        this.service = service;
        this.horseWarningService = horseWarningService;
        this.doubleGangerService = doubleGangerService;
    }

    public static void main(String[] args) {
        SpringApplication.run(TravScraperApplication.class, args);
    }

    @Override
    public void run(String... args) {
        //service.scrapeForeign();
        service.scrapeFuture();
        service.scrapeResultatPopupsOnly();
        service.scrape();
        horseWarningService.refreshWarnings(8);
        doubleGangerService.refreshDoubleGangers();
    }
}
