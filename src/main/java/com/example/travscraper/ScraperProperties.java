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
    //90-150
/*    private LocalDate startDateFuture = LocalDate.now().minusDays(0);
    private LocalDate endDateFuture = LocalDate.now().minusDays(0);
    private LocalDate startDateResults = LocalDate.now().minusDays(1);
    private LocalDate endDateResults = LocalDate.now().minusDays(1);*/

    private LocalDate startDateResults = LocalDate.now().minusDays(0);
    private LocalDate endDateResults = LocalDate.now().minusDays(0);
    private LocalDate startDateFuture = LocalDate.now().plusDays(1);
    private LocalDate endDateFuture = LocalDate.now().plusDays(1);
}
