package com.example.travscraper.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.Immutable;

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
}
