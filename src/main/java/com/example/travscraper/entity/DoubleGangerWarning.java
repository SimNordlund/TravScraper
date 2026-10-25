package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "double_ganger_warning",
        uniqueConstraints = @UniqueConstraint(columnNames = {"datum", "bankod", "lopp", "namn"})
)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class DoubleGangerWarning {

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

    @Column(name = "double_ganger", nullable = false, columnDefinition = "boolean default false")
    private boolean doubleGanger;

    @Column(name = "tid_99_warning", nullable = false, columnDefinition = "boolean default false")
    private boolean tid99Warning;

    @Column(name = "horse_warning", nullable = false, columnDefinition = "boolean default false")
    private boolean horseWarning;

    @Builder.Default
    @Column(name = "starts", nullable = false, columnDefinition = "bigint default 0")
    private Long starts = 0L;
}
