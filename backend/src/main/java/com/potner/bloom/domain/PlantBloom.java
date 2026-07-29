package com.potner.bloom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 꽃이 핀 것을 남긴 기록 한 건이다.
 *
 * <p>{@code Alert} 와 나눠 둔다. 이상 알림은 식물·지표별로 활성 1건만 존재해야 해서
 * {@code active_key} 로 막혀 있는데, 개화는 여러 번 일어나므로 같은 제약을 쓸 수 없다.
 *
 * <p>사진을 갖지 않는다. 개화한 날 사진은 {@code plant_photo.photo_date} 로 찾으면 되고,
 * 여기에 따로 두면 같은 날 사진이 둘 생겨 하루 한 장 정책과 충돌한다. 일기와 같은 이유다.
 */
@Entity
@Table(name = "plant_bloom")
public class PlantBloom {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "bloom_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @Column(name = "bloom_date", nullable = false)
    private LocalDate bloomDate;

    @Column(name = "note", length = 200)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, nullable = false)
    private BloomSource source;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /**
     * DB 기본값에 맡기지 않고 애플리케이션이 채운다. {@code Alert} 는 맡겨 두지만 그쪽은 저장
     * 직후에 응답으로 나가는 경로가 없다. 개화는 기록 API 가 만든 것을 그대로 돌려주므로
     * DB 가 채운 값을 다시 읽어오지 않으면 {@code createdAt} 이 null 로 나간다.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PlantBloom() {
    }

    public static PlantBloom record(
            String userId,
            String plantId,
            LocalDate bloomDate,
            String note,
            BloomSource source,
            LocalDateTime now
    ) {
        PlantBloom bloom = new PlantBloom();
        bloom.id = UUID.randomUUID().toString();
        bloom.userId = userId;
        bloom.plantId = plantId;
        bloom.bloomDate = bloomDate;
        bloom.note = normalize(note);
        bloom.source = source;
        bloom.createdAt = now;
        bloom.updatedAt = now;
        return bloom;
    }

    /**
     * 사용자가 확인했음을 기록한다. 다시 호출해도 처음 확인 시각을 유지한다.
     *
     * <p>{@code updated_at} 을 함께 옮긴다. 컬럼이 쓰기 가능해진 이상 Hibernate 가 UPDATE 문에
     * 옛 값을 그대로 실어 보내므로 MySQL 의 {@code ON UPDATE CURRENT_TIMESTAMP} 가 돌지 않는다.
     */
    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
            this.updatedAt = readAt;
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    /** 공백만 남은 메모는 없는 것으로 본다. 목록에서 빈 줄로 보이면 사용자가 오해한다. */
    private static String normalize(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getPlantId() {
        return plantId;
    }

    public LocalDate getBloomDate() {
        return bloomDate;
    }

    public String getNote() {
        return note;
    }

    public BloomSource getSource() {
        return source;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
