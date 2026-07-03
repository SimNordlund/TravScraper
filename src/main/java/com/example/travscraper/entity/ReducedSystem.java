package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "reducedsystem"
)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class ReducedSystem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "streck", precision = 6, scale = 2)
    private BigDecimal streck;

    @Column(name = "nr")
    private Integer nr;

    @Column(name = "lopp")
    private Integer lopp;

    @Column(name = "bankod", length = 20, nullable = false)
    private String banKod;

    @Column(name = "startDatum")
    private String startDatum;

    @Column(name = "streckTyp")
    private String streckTyp;

}
