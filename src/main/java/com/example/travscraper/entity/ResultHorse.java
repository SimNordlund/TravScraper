package com.example.travscraper.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.Immutable;


import jakarta.persistence.Column;

@Entity
@Table(name = "resultat")
@Immutable
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class ResultHorse {
    @Id
    private Long id;

    @Column(name = "datum")
    private Integer datum;

    @Column(name = "bankod")
    private String bankod;

    @Column(name = "lopp")
    private Integer lopp;

    @Column(name = "nr")
    private Integer nr;

    @Column(name = "namn")
    private String namn;

    @Column(name = "distans")
    private Integer distans;

    @Column(name = "spar")
    private Integer spar;

    @Column(name = "placering")
    private Integer placering;

    @Column(name = "tid")
    private Double tid;

    @Column(name = "startmetod")
    private String startmetod;

    @Column(name = "galopp") //denna behövs nog ej
    private String galopp;
}
