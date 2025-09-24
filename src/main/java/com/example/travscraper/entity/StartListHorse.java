package com.example.travscraper.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "startlista")
@Immutable
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class StartListHorse {

    @Id
    private Long id;

    @Column(name = "startdatum")
    private Integer startDatum;

    @Column(name = "bankod")
    private String banKod;

}
