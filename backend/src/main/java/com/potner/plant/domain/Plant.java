package com.potner.plant.domain;

import com.potner.user.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "plant")
public class Plant {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private PlantSpecies species;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "life_stage_id", nullable = false)
    private PlantLifeStage lifeStage;

    @Column(name = "nickname", length = 50, nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "plant_status", length = 20, nullable = false)
    private PlantStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected Plant() {
    }

    private Plant(
            String id,
            AppUser user,
            PlantSpecies species,
            PlantLifeStage lifeStage,
            String nickname,
            LocalDateTime now
    ) {
        this.id = id;
        this.user = user;
        this.species = species;
        this.lifeStage = lifeStage;
        this.nickname = nickname;
        this.status = PlantStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Plant create(
            AppUser user,
            PlantSpecies species,
            PlantLifeStage lifeStage,
            String nickname,
            LocalDateTime now
    ) {
        return new Plant(
                UUID.randomUUID().toString(),
                user,
                species,
                lifeStage,
                nickname.trim(),
                now
        );
    }

    public void updateNickname(String nickname, LocalDateTime now) {
        this.nickname = nickname.trim();
        this.updatedAt = now;
    }

    public void changeLifeStage(PlantLifeStage lifeStage, LocalDateTime now) {
        this.lifeStage = lifeStage;
        this.updatedAt = now;
    }

    public void delete(LocalDateTime now) {
        this.status = PlantStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public PlantSpecies getSpecies() {
        return species;
    }

    public PlantLifeStage getLifeStage() {
        return lifeStage;
    }

    public String getNickname() {
        return nickname;
    }

    public PlantStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}
