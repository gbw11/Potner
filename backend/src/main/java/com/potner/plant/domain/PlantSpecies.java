package com.potner.plant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plant_species")
public class PlantSpecies {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "species_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "scientific_name", length = 150)
    private String scientificName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected PlantSpecies() {
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getScientificName() {
        return scientificName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }
}
