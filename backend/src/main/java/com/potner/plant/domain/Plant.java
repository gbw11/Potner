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

import java.time.LocalDate;
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

    /**
     * 대표 사진이다. 사용자가 올린 것일 수도, 포토 로그에서 고른 장치 사진일 수도 있다.
     *
     * <p>엔티티가 아니라 식별자만 들고 있다. {@code plant_photo} 가 이미 {@code plant} 를
     * 참조하고 있어 연관을 양방향으로 만들면 순환 참조가 매핑까지 올라온다.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "representative_photo_id", length = 36, columnDefinition = "char(36)")
    private String representativePhotoId;

    /** 사용자가 데려온 날짜. 앱 등록일({@code createdAt})과 다르며 모를 수도 있어 비어 있을 수 있다. */
    @Column(name = "adopted_date")
    private LocalDate adoptedDate;

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
            LocalDate adoptedDate,
            LocalDateTime now
    ) {
        this.id = id;
        this.user = user;
        this.species = species;
        this.lifeStage = lifeStage;
        this.nickname = nickname;
        this.adoptedDate = adoptedDate;
        this.status = PlantStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Plant create(
            AppUser user,
            PlantSpecies species,
            PlantLifeStage lifeStage,
            String nickname,
            LocalDate adoptedDate,
            LocalDateTime now
    ) {
        return new Plant(
                UUID.randomUUID().toString(),
                user,
                species,
                lifeStage,
                nickname.trim(),
                adoptedDate,
                now
        );
    }

    public void updateNickname(String nickname, LocalDateTime now) {
        this.nickname = nickname.trim();
        this.updatedAt = now;
    }

    public void updateAdoptedDate(LocalDate adoptedDate, LocalDateTime now) {
        this.adoptedDate = adoptedDate;
        this.updatedAt = now;
    }

    public void changeLifeStage(PlantLifeStage lifeStage, LocalDateTime now) {
        this.lifeStage = lifeStage;
        this.updatedAt = now;
    }

    /** 대표 사진을 바꾼다. 이전 사진의 정리는 호출자가 판단한다. */
    public void changeRepresentativePhoto(String photoId, LocalDateTime now) {
        this.representativePhotoId = photoId;
        this.updatedAt = now;
    }

    /** 대표 사진을 없앤다. 대체 이미지는 서버가 정하지 않고 앱이 고른다. */
    public void clearRepresentativePhoto(LocalDateTime now) {
        this.representativePhotoId = null;
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

    public String getRepresentativePhotoId() {
        return representativePhotoId;
    }

    public String getNickname() {
        return nickname;
    }

    public LocalDate getAdoptedDate() {
        return adoptedDate;
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
