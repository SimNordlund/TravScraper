package com.example.travscraper;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.time.LocalDate;


@Configuration
@ConfigurationProperties(prefix = "scraper")
@Getter

@Setter
public class ScraperProperties {

    private LocalDate startDateResults = LocalDate.now().minusDays(2);
    private LocalDate endDateResults = LocalDate.now().minusDays(1);

    private LocalDate startDateForeign = LocalDate.now().minusDays(1);
    private LocalDate endDateResultForeign = LocalDate.now().minusDays(1);

    private LocalDate startDateFuture = LocalDate.now().minusDays(1);
    private LocalDate endDateFuture = LocalDate.now().plusDays(1);

    private LocalDate startDateResultatPopup = LocalDate.now().minusDays(1);
    private LocalDate endDateResultatPopup = LocalDate.now().plusDays(1);

    private LocalDate startDateReducedSystem = LocalDate.now().plusDays(1);
    private LocalDate endDateReducedSystem = LocalDate.now().plusDays(3);
}
