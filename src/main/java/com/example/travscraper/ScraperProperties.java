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
    //private LocalDate startDateFuture = LocalDate.now().minusDays(1);
    //private LocalDate endDateFuture = LocalDate.now().minusDays(0);

    private LocalDate startDateResults = LocalDate.now().minusDays(5);
    private LocalDate endDateResults = LocalDate.now().minusDays(1);

    private LocalDate startDateForeign = LocalDate.now().minusDays(5);
    private LocalDate endDateResultForeign = LocalDate.now().minusDays(1);

    //private LocalDate startDateResultatPopup = LocalDate.now().minusDays(1);
    //private LocalDate endDateResultatPopup = LocalDate.now().minusDays(0);

    // ------------------------------------------------------------------------ //
    // ------------------------------------------------------------------------ //
    // ------------------------------------------------------------------------ //

    private LocalDate startDateFuture = LocalDate.now().minusDays(1);
    private LocalDate endDateFuture = LocalDate.now().plusDays(1);
/*    private LocalDate startDateFuture = LocalDate.now().minusDays(241); // 241
    private LocalDate endDateFuture = LocalDate.now().minusDays(197); //197*/

    private LocalDate startDateResultatPopup = LocalDate.now().minusDays(1);
    private LocalDate endDateResultatPopup = LocalDate.now().plusDays(1);
/*    private LocalDate startDateResultatPopup = LocalDate.now().minusDays(200); //240 Råkade köra denna xd
    private LocalDate endDateResultatPopup = LocalDate.now().minusDays(96); //197*/
}