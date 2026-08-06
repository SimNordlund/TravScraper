package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "tid_99_warning",
        uniqueConstraints = @UniqueConstraint(columnNames = {"datum", "bankod", "lopp", "nr", "namn"})
)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class Tid99Warning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datum", nullable = false)
    private Integer datum;

    @Column(name = "bankod", nullable = false, length = 20)
    private String bankod;

    @Column(name = "lopp", nullable = false)
    private Integer lopp;

    @Column(name = "nr", nullable = false)
    private Integer nr;

    @Column(name = "namn", nullable = false, length = 50)
    private String namn;

    @Column(name = "placering")
    private Integer placering;

    @Column(name = "tid")
    private Double tid;

    @PrePersist
    @PreUpdate
    private void applyNonNullDefaults() {
        if (datum == null) datum = 0;
        if (bankod == null) bankod = "";
        if (lopp == null) lopp = 0;
        if (nr == null) nr = 0;
        if (namn == null) namn = "";
    }
}