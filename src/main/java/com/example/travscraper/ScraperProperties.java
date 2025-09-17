package com.example.travscraper;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "scraper")
@Getter
@Setter
public class ScraperProperties {

    private List<String> tracks = List.of(
            "arvika, axevalla, bergsaker, boden, bollnas, dannero, eskilstuna, farjestad," +
                    "gavle, hagmyren, halmstad, hoting, jagersro, kalmar, karlshamn, lindesberg," +
                    "lycksele, mantorp, oviken, romme, rattvik, skelleftea, solvalla, solanget," +
                    "tingsryd, umea, vaggeryd, visby, aby, amal, arjang, orebro, ostersund");

    private LocalDate startDateFuture = LocalDate.now().minusDays(0);
    private LocalDate endDateFuture = LocalDate.now().minusDays(0);

    private LocalDate startDateResults = LocalDate.now().minusDays(2);
    private LocalDate endDateResults = LocalDate.now().minusDays(1);

//    private LocalDate startDateFuture = LocalDate.now().plusDays(1);
//    private LocalDate endDateFuture = LocalDate.now().plusDays(1);

//    private LocalDate startDateResults = LocalDate.now().minusMonths(14);
//    private LocalDate endDateResults = LocalDate.now().minusMonths(13);
}
