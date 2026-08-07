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
import java.util.Optional;

/**
 * 사진 파일을 EC2 디스크에 쓰고 세 가지 크기를 만든다.
 *
 * <p>디렉터리 구조는 {@code {root}/{plantId}/{photoId}/{original|playback|thumbnail}.jpg} 다.
 * 경로에 UUID 두 개가 들어가 추측할 수 없다. 정적 서빙에는 인증이 걸리지 않으므로 이 점이 중요하다.
 *
 * <p>축소는 JDK 내장 ImageIO 로 한다. 의존성을 늘리지 않는 대신 EXIF 회전 정보는 적용하지 않는다.
 * picamera2 가 만드는 사진은 이미 정방향이므로 현재 수집 경로에서는 문제가 없지만,
 * 회전 정보를 넣는 카메라를 붙이면 사진이 눕는다.
 *
 * <p>장치 사진만 {@code potner.photo.device-rotate-degrees} 만큼 돌려서 저장한다. 카메라가
 * 거꾸로 달려 있어 올라오는 사진이 뒤집혀 있기 때문이다. 사용자가 올리는 대표 사진은 돌리지
 * 않는다 — 휴대폰 사진은 정방향이다.
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
     * 사용자가 올린 사진을 그대로 저장한다. 휴대폰 사진은 정방향이므로 돌리지 않는다.
     */
    public StoredPhoto store(String plantId, String photoId, byte[] content) {
        return store(plantId, photoId, content, 0);
    }

    /**
     * 장치가 올린 사진을 설정된 각도만큼 돌려서 저장한다.
     *
     * <p>카메라가 거꾸로 달려 있어 올라오는 사진이 뒤집혀 있다. 표시하는 쪽이 아니라 여기서
     * 바로잡는 이유는 화면이 네 곳이고, 생장 단계 추론도 같은 파일(재생본)을 읽기 때문이다.
     */
    public StoredPhoto storeFromDevice(String plantId, String photoId, byte[] content) {
        return store(plantId, photoId, content, properties.deviceRotateDegrees());
    }

    /**
     * 원본과 축소본 두 개를 저장하고 상대 경로를 돌려준다.
     *
     * <p>중간에 실패하면 이미 쓴 파일을 지운다. 남겨두면 DB 행이 없는 고아 파일이 쌓이고
     * 디스크만 차지한다.
     */
    private StoredPhoto store(String plantId, String photoId, byte[] content, int rotateDegrees) {
        if (content.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_PHOTO);
        }
        if (content.length > properties.maxUploadBytes()) {
            throw new BusinessException(ErrorCode.PHOTO_TOO_LARGE);
        }

        BufferedImage original = readImage(content);
        BufferedImage oriented = rotate(original, rotateDegrees);
        Path directory = Path.of(properties.root(), plantId, photoId);
        long originalByteSize;
        try {
            Files.createDirectories(directory);
            Path originalPath = directory.resolve(ORIGINAL_FILE_NAME);
            if (oriented == original) {
                // 돌릴 것이 없으면 업로드 바이트를 그대로 쓴다. 다시 인코딩하면 화질만 잃는다.
                Files.write(originalPath, content);
                originalByteSize = content.length;
            } else {
                writeJpeg(oriented, originalPath);
                // 다시 인코딩했으므로 업로드 크기가 아니라 실제로 쓴 크기를 남긴다.
                originalByteSize = Files.size(originalPath);
            }
            writeScaled(oriented, directory.resolve(PLAYBACK_FILE_NAME), properties.playbackMaxPixels());
            writeScaled(oriented, directory.resolve(THUMBNAIL_FILE_NAME), properties.thumbnailMaxPixels());
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
                // 90·270 도면 가로세로가 뒤바뀌므로 돌린 뒤 값을 쓴다.
                oriented.getWidth(),
                oriented.getHeight(),
                originalByteSize
        );
    }

    /**
     * 사진 하나의 파일 세 개와 그 디렉터리를 지운다.
     *
     * <p>대표 사진을 바꿀 때 이전 사용자 업로드를 정리하는 데 쓴다. 그 사진은 포토 로그에도
     * 보이지 않으므로 남겨두면 어디서도 닿을 수 없는 파일이 디스크에만 쌓인다.
     *
     * <p>사용자가 포토 로그에서 사진을 지울 때도 쓴다({@code DELETE /plants/{id}/photos/{photoId}}).
     * 그쪽은 장치 사진도 대상이며, 지운 뒤에는 타임랩스에서 그 날이 빠진다.
     *
     * <p>실패해도 예외를 내지 않는다. 파일 정리가 실패했다고 대표 사진 변경까지 되돌리는 것은
     * 사용자에게 손해다. DB 행이 사라졌으므로 남은 파일은 어차피 접근 경로가 없다.
     */
    public void delete(String plantId, String photoId) {
        deleteQuietly(Path.of(properties.root(), plantId, photoId));
    }

    /** 상대 경로를 앱이 바로 쓸 수 있는 URL 로 만든다. */
    public String toUrl(String relativePath) {
        return properties.baseUrl().replaceAll("/+$", "") + "/" + relativePath;
    }

    /**
     * 생장 단계 판정에 넘길 이미지를 읽는다.
     *
     * <p>원본이 아니라 <strong>재생용 변형</strong>을 읽는다. 추론은 640px 로 입력을 줄여서 쓰므로
     * 원본 해상도가 판정을 더 낫게 만들지 않는다. 원본은 최대 10 MiB 라 그대로 실어 보내면
     * 전송과 힙만 낭비한다.
     *
     * <p>업로드 응답을 이미 돌려준 뒤 비동기로 읽는다. 그 사이 사진이 지워질 수 있으므로
     * 파일이 없는 것은 정상이며 빈 값으로 돌아온다.
     */
    public Optional<byte[]> readPlayback(String plantId, String photoId) {
        Path path = Path.of(properties.root(), plantId, photoId, PLAYBACK_FILE_NAME);
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException exception) {
            // 경로는 남기지 않는다. 정적 서빙에 인증이 없으므로 로그에 경로가 남으면 추측할 수
            // 없다는 전제가 약해진다.
            log.warn("Cannot read the playback photo: plantId={}, photoId={}", plantId, photoId, exception);
            return Optional.empty();
        }
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
     * 90도 단위로 돌린 새 이미지를 만든다. 0도면 원본을 그대로 돌려준다 — 호출부가 같은
     * 객체인지로 "돌릴 것이 없었다"를 판별해 원본 바이트를 그대로 쓴다.
     */
    private BufferedImage rotate(BufferedImage source, int degrees) {
        int normalized = Math.floorMod(degrees, 360);
        if (normalized == 0) {
            return source;
        }

        boolean quarterTurn = normalized == 90 || normalized == 270;
        int width = quarterTurn ? source.getHeight() : source.getWidth();
        int height = quarterTurn ? source.getWidth() : source.getHeight();

        BufferedImage rotated = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rotated.createGraphics();
        try {
            // 회전은 원본 중심을 기준으로 하고, 90·270 도에서 달라진 캔버스 크기만큼 먼저 옮긴다.
            graphics.translate((width - source.getWidth()) / 2.0, (height - source.getHeight()) / 2.0);
            graphics.rotate(Math.toRadians(normalized), source.getWidth() / 2.0, source.getHeight() / 2.0);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rotated;
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
        writeJpeg(scaled, destination);
    }

    private void writeJpeg(BufferedImage image, Path destination) throws IOException {
        if (!ImageIO.write(image, "jpg", destination.toFile())) {
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
