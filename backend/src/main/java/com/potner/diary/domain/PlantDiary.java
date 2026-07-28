package com.potner.diary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 성장 일기 한 편이다.
 *
 * <p>사용자가 쓰지 않는다. 매일 정해진 시각에 LLM 이 그날의 센서·알림·사진을 근거로 한 편씩
 * 쓴다. 그래서 작성자 컬럼이 없고, 하루 한 편이라는 제약이 배치 재실행도 함께 막는다.
 *
 * <p>사진은 여기 두지 않는다. 장치가 하루 한 장 찍은 사진을 {@code photo_date} 로 찾아 쓰면
 * 되므로 컬럼이 필요 없다. 사용자가 일기에 따로 올리게 하면 같은 날 사진이 둘 생겨 하루 한 장
 * 정책과 충돌한다.
 */
@Entity
@Table(name = "plant_diary")
public class PlantDiary {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "diary_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @Column(name = "diary_date", nullable = false)
    private LocalDate diaryDate;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected PlantDiary() {
    }

    public static PlantDiary write(
            String plantId,
            LocalDate diaryDate,
            String title,
            String content
    ) {
        PlantDiary diary = new PlantDiary();
        diary.id = UUID.randomUUID().toString();
        diary.plantId = plantId;
        diary.diaryDate = diaryDate;
        diary.title = title.trim();
        diary.content = content.trim();
        return diary;
    }

    public String getId() {
        return id;
    }

    public String getPlantId() {
        return plantId;
    }

    public LocalDate getDiaryDate() {
        return diaryDate;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
