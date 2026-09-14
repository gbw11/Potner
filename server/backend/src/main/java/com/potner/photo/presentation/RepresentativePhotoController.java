package com.potner.photo.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.photo.application.PhotoService;
import com.potner.photo.dto.PhotoResponse;
import com.potner.photo.dto.SelectRepresentativePhotoRequest;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 식물 대표 사진이다. 식물 프로필과 목록 썸네일에 쓰인다.
 *
 * <p>지정 경로가 둘이다. 사용자가 앨범이나 카메라에서 새로 올리거나, 포토 로그에 이미 있는
 * 장치 사진 중에서 고른다. 어느 쪽이든 결과는 {@code plant_photo} 행 하나를 가리키는 것이다.
 *
 * <p>여기서 올린 사진은 포토 로그와 타임랩스에 나타나지 않는다. 그쪽은 장치가 하루 한 장씩
 * 같은 구도로 찍은 성장 기록이고, 사용자가 아무 때나 올린 사진이 섞이면 타임랩스가 깨진다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/representative-photo")
@Tag(name = "식물 대표 사진", description = "식물 프로필에 쓰이는 대표 사진 지정 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class RepresentativePhotoController {

    private final PhotoService photoService;

    public RepresentativePhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "대표 사진 새로 올리기",
            description = """
                    사용자가 앨범이나 카메라에서 고른 이미지를 올려 대표 사진으로 지정한다.

                    장치 업로드와 달리 하루 한 장 제한이 없다. 그 제한은 타임랩스 프레임 간격을
                    위한 것이고 대표 사진은 프레임이 아니다.

                    이전 대표 사진이 사용자가 올린 것이었으면 함께 지운다. 포토 로그에도 보이지
                    않아 남겨두면 닿을 수 없는 파일이 쌓이기 때문이다. 장치 사진이었으면 대표에서
                    내려올 뿐 포토 로그에는 그대로 남는다.

                    이미지로 읽히지 않으면 400, 허용 크기를 넘으면 413 이다.
                    """
    )
    public PhotoResponse upload(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestPart("file") MultipartFile file
    ) {
        return photoService.uploadRepresentativePhoto(principal.userId(), plantId, readBytes(file));
    }

    @PatchMapping
    @Operation(
            summary = "포토 로그에서 대표 사진 고르기",
            description = """
                    이미 저장된 사진을 대표로 지정한다. 포토 로그 화면에서 한 장을 고르는 흐름이다.

                    photoId 가 그 식물의 사진이 아니면 404 다. 사진 URL 에는 인증이 걸리지 않으므로
                    남의 식물 사진을 지정할 수 있으면 그대로 유출 경로가 된다.
                    """
    )
    public PhotoResponse select(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody SelectRepresentativePhotoRequest request
    ) {
        return photoService.selectRepresentativePhoto(
                principal.userId(),
                plantId,
                request.photoId()
        );
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "대표 사진 해제",
            description = """
                    대표 사진을 없앤다. 사용자가 올린 사진이었으면 함께 지운다.

                    해제 후 무엇을 보여줄지는 서버가 정하지 않는다. 응답의 representativePhoto 가
                    null 이 되고, 최신 장치 사진을 쓸지 기본 이미지를 쓸지는 앱이 고른다.

                    이미 없어도 204 다.
                    """
    )
    public void clear(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        photoService.clearRepresentativePhoto(principal.userId(), plantId);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read uploaded photo", exception);
        }
    }
}
