package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(name = "kontroll")
public class HorseWarning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datum")
    private int date;
    @Column(name = "namn")
    private String name;
    @Column(name = "starter")
    private int starts;
}
