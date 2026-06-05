package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "double_gangers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"date", "track1", "track2", "horse_name"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoubleGangers {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date", nullable = false)
    private Integer date;

    @Column(name = "track1", nullable = false, length = 20)
    private String track1;

    @Column(name = "track2", nullable = false, length = 20)
    private String track2;

    @Column(name = "horse_name", nullable = false, length = 50)
    private String horseName;

    @PrePersist
    @PreUpdate
    private void applyNonNullDefaults() {
        if (date == null) date = 0;
        if (track1 == null) track1 = "";
        if (track2 == null) track2 = "";
        if (horseName == null) horseName = "";
    }
}
