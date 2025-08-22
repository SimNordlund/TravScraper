package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity //Changed!
@Table( //Changed!
        name = "future_horse", //Changed!
        uniqueConstraints = @UniqueConstraint( //Changed!
                columnNames = {"date", "track", "lap", "number_of_horse"} //Changed!
        )
)
@Getter //Changed!
@Setter //Changed!
@NoArgsConstructor //Changed!
@AllArgsConstructor //Changed!
@Builder //Changed!
public class FutureHorse { //Changed!

    @Id //Changed!
    @GeneratedValue(strategy = GenerationType.IDENTITY) //Changed!
    private Long id; //Changed!

    private LocalDate date; //Changed!
    private String lap; //Changed!

    @Column(name = "name_of_horse") //Changed!
    private String nameOfHorse; //Changed!

    @Column(name = "number_of_horse") //Changed!
    private String numberOfHorse; //Changed!

    @Column(name = "v_odds") //Changed!
    private String vOdds; //Changed!

    private String track; //Changed!
}
