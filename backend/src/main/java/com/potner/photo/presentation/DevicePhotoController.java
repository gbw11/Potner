package com.potner.photo.presentation;

import com.potner.photo.application.PhotoService;
import com.potner.photo.dto.PhotoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;

/**
 * 장치가 사진을 올리는 경로다.
 *
 * <p>사용자 JWT 대신 업로드 토큰으로 인증하므로 Security 설정에서 JWT 인증을 면제하고
 * 서비스가 토큰을 직접 검증한다. <strong>plantId 를 받지 않는다</strong> — 어느 식물인지는
 * 로봇의 활성 배정에서 서버가 정한다. 장치가 지정할 수 있으면 토큰 하나로 남의 식물에
 * 사진을 넣을 수 있다.
 */
@RestController
@RequestMapping("/api/v1/device/photos")
@Tag(name = "장치 사진 업로드", description = "라즈베리가 촬영한 사진을 업로드하는 API")
public class DevicePhotoController {

    private final PhotoService photoService;

    public DevicePhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "장치 사진 업로드",
            description = """
                    X-Device-Token 헤더에 로봇 등록 시 발급된 업로드 토큰을 넣는다.
                    토큰 원문은 등록 응답에서만 볼 수 있으며 잃어버리면 재발급해야 한다.

                    plantId 는 받지 않는다. 로봇의 활성 배정에서 서버가 정한다. 배정이 없으면
                    404 (PLANT_ASSIGNMENT_NOT_FOUND) 다.

                    capturedAt 은 오프셋을 포함한 ISO-8601 이어야 한다. 생략하면 수신 시각을 쓴다.
                    쿼리 스트링에서 '+' 가 공백으로 해석되므로 Z 형식을 권장한다.

                    하루 한 장만 저장한다. 타임랩스 프레임 간격을 일정하게 유지하기 위함이며
                    같은 날짜에 두 번 올리면 409 다. 이미지로 읽히지 않으면 400,
                    허용 크기를 넘으면 413 이다.
                    """
    )
    public PhotoResponse upload(
            @RequestHeader("X-Device-Token") String uploadToken,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) OffsetDateTime capturedAt
    ) {
        return photoService.uploadFromDevice(uploadToken, capturedAt, readBytes(file));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read uploaded photo", exception);
        }
    }
}
