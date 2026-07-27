package com.potner.photo.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.photo.config.PhotoStorageProperties;
import com.potner.photo.domain.StoredPhotoPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 사진 파일을 EC2 디스크에 쓰고 세 가지 크기를 만든다.
 *
 * <p>디렉터리 구조는 {@code {root}/{plantId}/{photoId}/{original|playback|thumbnail}.jpg} 다.
 * 경로에 UUID 두 개가 들어가 추측할 수 없다. 정적 서빙에는 인증이 걸리지 않으므로 이 점이 중요하다.
 *
 * <p>축소는 JDK 내장 ImageIO 로 한다. 의존성을 늘리지 않는 대신 EXIF 회전 정보는 적용하지 않는다.
 * picamera2 가 만드는 사진은 이미 정방향이므로 현재 수집 경로에서는 문제가 없지만,
 * 회전 정보를 넣는 카메라를 붙이면 사진이 눕는다.
 */
@Component
public class PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorage.class);
    private static final String ORIGINAL_FILE_NAME = "original.jpg";
    private static final String PLAYBACK_FILE_NAME = "playback.jpg";
    private static final String THUMBNAIL_FILE_NAME = "thumbnail.jpg";

    private final PhotoStorageProperties properties;

    public PhotoStorage(PhotoStorageProperties properties) {
        this.properties = properties;
    }

    /**
     * 원본과 축소본 두 개를 저장하고 상대 경로를 돌려준다.
     *
     * <p>중간에 실패하면 이미 쓴 파일을 지운다. 남겨두면 DB 행이 없는 고아 파일이 쌓이고
     * 디스크만 차지한다.
     */
    public StoredPhoto store(String plantId, String photoId, byte[] content) {
        if (content.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_PHOTO);
        }
        if (content.length > properties.maxUploadBytes()) {
            throw new BusinessException(ErrorCode.PHOTO_TOO_LARGE);
        }

        BufferedImage original = readImage(content);
        Path directory = Path.of(properties.root(), plantId, photoId);
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(ORIGINAL_FILE_NAME), content);
            writeScaled(original, directory.resolve(PLAYBACK_FILE_NAME), properties.playbackMaxPixels());
            writeScaled(original, directory.resolve(THUMBNAIL_FILE_NAME), properties.thumbnailMaxPixels());
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(directory);
            if (exception instanceof IOException ioException) {
                throw new UncheckedIOException("Failed to store photo: photoId=" + photoId, ioException);
            }
            throw (RuntimeException) exception;
        }

        return new StoredPhoto(
                new StoredPhotoPaths(
                        relativePath(plantId, photoId, ORIGINAL_FILE_NAME),
                        relativePath(plantId, photoId, PLAYBACK_FILE_NAME),
                        relativePath(plantId, photoId, THUMBNAIL_FILE_NAME)
                ),
                original.getWidth(),
                original.getHeight(),
                content.length
        );
    }

    /** 상대 경로를 앱이 바로 쓸 수 있는 URL 로 만든다. */
    public String toUrl(String relativePath) {
        return properties.baseUrl().replaceAll("/+$", "") + "/" + relativePath;
    }

    /** 이미지로 읽히지 않으면 확장자와 무관하게 거부한다. Content-Type 은 신뢰할 수 없다. */
    private BufferedImage readImage(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                throw new BusinessException(ErrorCode.INVALID_PHOTO);
            }
            return image;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INVALID_PHOTO);
        }
    }

    /**
     * 긴 변을 {@code maxPixels} 에 맞춰 비율을 유지하며 줄인다.
     * 원본이 이미 작으면 확대하지 않고 그대로 다시 쓴다.
     */
    private void writeScaled(BufferedImage source, Path destination, int maxPixels) throws IOException {
        int longestSide = Math.max(source.getWidth(), source.getHeight());
        double ratio = longestSide <= maxPixels ? 1.0 : (double) maxPixels / longestSide;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(scaled, "jpg", destination.toFile())) {
            throw new IOException("No JPEG writer available");
        }
    }

    private String relativePath(String plantId, String photoId, String fileName) {
        return plantId + "/" + photoId + "/" + fileName;
    }

    /** 정리 실패가 업로드 실패를 덮어쓰지 않도록 로그만 남긴다. */
    private void deleteQuietly(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("Failed to clean up photo file: path={}", path, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("Failed to clean up photo directory: path={}", directory, exception);
        }
    }

    public record StoredPhoto(StoredPhotoPaths paths, int width, int height, long byteSize) {
    }
}
