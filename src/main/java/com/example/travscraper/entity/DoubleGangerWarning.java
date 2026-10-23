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
}
