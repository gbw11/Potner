package com.potner.plant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plant_category")
public class PlantCategory {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected PlantCategory() {
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
