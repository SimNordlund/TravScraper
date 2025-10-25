package com.example.travscraper.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.Immutable;


import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;

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
    //Behövs nedan strategy?
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "datum", nullable = false)
    private Integer datum;

    @Column(name = "bankod", length = 2, nullable = false)
    private String bankod;

    @Column(name = "lopp", nullable = false)
    private Integer lopp;

    @Column(name = "nr", nullable = false)
    private Integer nr;

    @Column(name = "namn", length = 50, nullable = false)
    private String namn;

    @Column(name = "distans")
    private Integer distans;

    @Column(name = "placering")
    private Integer placering;

    @Column(name = "tid")
    private Float tid;

    @Column(name = "startmetod", length = 1)
    private String startmetod;

    @Column(name = "galopp", length = 1)
    private String galopp;
}
