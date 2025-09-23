package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(
        name = "future_horse",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"date", "track", "lap", "number_of_horse"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FutureHorse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;
    private String lap;

    @Column(name = "name_of_horse")
    private String nameOfHorse;

    @Column(name = "number_of_horse")
    private String numberOfHorse;

    @Column(name = "v_odds")
    private String vOdds;

    private String track;
}
