package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(
        name = "scraped_horse",
        uniqueConstraints = @UniqueConstraint(
                columnNames = { "date", "track", "lap", "number_of_horse" })
)
@IdClass(ScrapedHorseKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapedHorse {

    @Id
    private LocalDate date;
    @Id
    private String track;
    @Id
    private String lap;
    @Id
    private String numberOfHorse;

    private String nameOfHorse;
    private String placement;
    private String vOdds;
    private String pOdds;
    private String trioOdds;
}
