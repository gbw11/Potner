package com.potner.photo.domain;

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
 * 식물 성장 사진 한 장이다.
 *
 * <p>URL 대신 상대 경로를 저장한다. 도메인과 스킴이 환경마다 다르고 나중에 바뀔 수 있으므로
 * 조회 시 base-url 프로퍼티와 합쳐 URL 을 만든다.
 */
@Entity
@Table(name = "plant_photo")
public class PlantPhoto {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "photo_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_robot_id", length = 36, columnDefinition = "char(36)")
    private String sourceRobotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, nullable = false)
    private PhotoSource source;

    @Column(name = "photo_date", nullable = false)
    private LocalDate photoDate;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "original_path", length = 500, nullable = false)
    private String originalPath;

    @Column(name = "playback_path", length = 500, nullable = false)
    private String playbackPath;

    @Column(name = "thumbnail_path", length = 500, nullable = false)
    private String thumbnailPath;

    @Column(name = "width", nullable = false, columnDefinition = "int unsigned")
    private int width;

    @Column(name = "height", nullable = false, columnDefinition = "int unsigned")
    private int height;

    @Column(name = "byte_size", nullable = false, columnDefinition = "int unsigned")
    private long byteSize;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected PlantPhoto() {
    }

    /** 장치가 촬영한 성장 사진이다. 포토 로그와 타임랩스에 쓰인다. */
    public static PlantPhoto fromDevice(
            String photoId,
            String plantId,
            String sourceRobotId,
            LocalDate photoDate,
            LocalDateTime capturedAt,
            StoredPhotoPaths paths,
            int width,
            int height,
            long byteSize
    ) {
        return create(
                photoId,
                plantId,
                sourceRobotId,
                PhotoSource.DEVICE,
                photoDate,
                capturedAt,
                paths,
                width,
                height,
                byteSize
        );
    }

    /**
     * 사용자가 앱에서 올린 사진이다. 출처 로봇이 없다.
     *
     * <p>포토 로그에 섞이지 않으므로 {@code photoDate} 는 조회 키가 아니라 기록용이다.
     * 그래도 컬럼이 {@code NOT NULL} 이라 업로드 시각의 서비스 날짜를 넣는다.
     */
    public static PlantPhoto fromUser(
            String photoId,
            String plantId,
            LocalDate photoDate,
            LocalDateTime capturedAt,
            StoredPhotoPaths paths,
            int width,
            int height,
            long byteSize
    ) {
        return create(
                photoId,
                plantId,
                null,
                PhotoSource.USER,
                photoDate,
                capturedAt,
                paths,
                width,
                height,
                byteSize
        );
    }

    private static PlantPhoto create(
            String photoId,
            String plantId,
            String sourceRobotId,
            PhotoSource source,
            LocalDate photoDate,
            LocalDateTime capturedAt,
            StoredPhotoPaths paths,
            int width,
            int height,
            long byteSize
    ) {
        PlantPhoto photo = new PlantPhoto();
        photo.id = photoId;
        photo.plantId = plantId;
        photo.sourceRobotId = sourceRobotId;
        photo.source = source;
        photo.photoDate = photoDate;
        photo.capturedAt = capturedAt;
        photo.originalPath = paths.originalPath();
        photo.playbackPath = paths.playbackPath();
        photo.thumbnailPath = paths.thumbnailPath();
        photo.width = width;
        photo.height = height;
        photo.byteSize = byteSize;
        return photo;
    }

    /** 저장 경로에 UUID 두 개가 들어가 추측할 수 없다. 정적 서빙에는 인증이 걸리지 않으므로 중요하다. */
    public static String newPhotoId() {
        return UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public String getPlantId() {
        return plantId;
    }

    public String getSourceRobotId() {
        return sourceRobotId;
    }

    public PhotoSource getSource() {
        return source;
    }

    public boolean isUserUploaded() {
        return source == PhotoSource.USER;
    }

    public LocalDate getPhotoDate() {
        return photoDate;
    }

    public LocalDateTime getCapturedAt() {
        return capturedAt;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public String getPlaybackPath() {
        return playbackPath;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getByteSize() {
        return byteSize;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
