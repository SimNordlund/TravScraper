package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table( //Changed!
        name = "resultat", //Changed!
        uniqueConstraints = @UniqueConstraint(columnNames = {"datum","bankod","lopp","namn"}) //Changed!
) //Changed!
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class ResultHorse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datum", nullable = false)
    private Integer datum;

    @Column(name = "bankod", length = 20, nullable = false) //Changed!
    private String bankod;

    @Column(name = "lopp", nullable = false)
    private Integer lopp;

    @Builder.Default //Changed!
    @Column(name = "nr", nullable = false) //Changed!
    private Integer nr = 0; //Changed!

    @Builder.Default
    @Column(name = "namn", nullable = false, length = 50)
    private String namn = "";

    @Column(name = "distans")
    private Integer distans;

    @Column(name = "spar")
    private Integer spar;

    @Column(name = "placering")
    private Integer placering;

    @Column(name = "tid")
    private Double tid;

    @Builder.Default
    @Column(name = "startmetod", nullable = false, length = 1)
    private String startmetod = "";

    @Builder.Default
    @Column(name = "galopp", nullable = false, length = 1)
    private String galopp = "";

    @Builder.Default //Changed!
    @Column(name = "underlag", nullable = false, length = 2) //Changed!
    private String underlag = ""; //Changed!

    @Builder.Default //Changed!
    @Column(name = "pris", nullable = false) //Changed!
    private Integer pris = 0; //Changed!

    @Builder.Default //Changed!
    @Column(name = "odds", nullable = false) //Changed!
    private Integer odds = 999; //Changed!

    @Builder.Default //Changed!
    @Column(name = "kusk", nullable = false, length = 80) //Changed!
    private String kusk = ""; //Changed!
}
