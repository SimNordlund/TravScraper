package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "resultat",
        uniqueConstraints = @UniqueConstraint(columnNames = {"datum","bankod","lopp","namn"})
)
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

    @Column(name = "bankod", length = 20, nullable = false)
    private String bankod;

    @Column(name = "lopp", nullable = false)
    private Integer lopp;

    @Builder.Default
    @Column(name = "nr", nullable = false)
    private Integer nr = 0;

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

    @Builder.Default
    @Column(name = "underlag", nullable = false, length = 3)
    private String underlag = "";

    @Builder.Default
    @Column(name = "pris", nullable = false)
    private Integer pris = 0;

    @Builder.Default
    @Column(name = "odds", nullable = false)
    private Integer odds = 999;

    @Builder.Default
    @Column(name = "kusk", nullable = false, length = 80)
    private String kusk = "";

    @PrePersist
    @PreUpdate
    private void applyNonNullDefaults() {
        if (nr == null) nr = 0;
        if (namn == null) namn = "";
        if (startmetod == null) startmetod = "";
        if (galopp == null) galopp = "";
        if (underlag == null) underlag = "";
        if (pris == null) pris = 0;
        if (odds == null) odds = 999;
        if (kusk == null) kusk = "";
    }
}
