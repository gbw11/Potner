package com.potner.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "robot")
public class Robot {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    protected Robot() {
    }

    public String getId() {
        return id;
    }
}
