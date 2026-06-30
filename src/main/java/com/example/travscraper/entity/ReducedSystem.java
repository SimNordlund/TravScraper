package com.example.travscraper.entity;

import jakarta.persistence.*;
import lombok.*;

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
}
