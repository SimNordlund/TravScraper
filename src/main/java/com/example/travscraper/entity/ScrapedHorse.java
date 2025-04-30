package com.example.travscraper.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.*;

import java.time.LocalDate;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter @Setter @Builder
public class ScrapedHorse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;
    private String track;
    private String lap;           // “lopp” – kept as String for now
    private String numberOfHorse;
    private String nameOfHorse;
    private String placement;
    private String odds;
}


