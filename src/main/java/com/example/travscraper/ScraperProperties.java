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

    private LocalDate startDate = LocalDate.now().minusDays(328); //290
    private LocalDate endDate = LocalDate.now().minusDays(290); //233
}
