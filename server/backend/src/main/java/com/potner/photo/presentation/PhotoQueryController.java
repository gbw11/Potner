package com.potner.photo.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.photo.application.PhotoService;
import com.potner.photo.dto.PhotoListResponse;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/plants/{plantId}/photos")
@Tag(name = "성장 사진", description = "포토 로그 및 타임랩스 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PhotoQueryController {

    private final PhotoService photoService;

    public PhotoQueryController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @GetMapping
    @Operation(
            summary = "기간 내 성장 사진 조회",
            description = """
                    포토 로그 그리드와 타임랩스가 같은 응답을 쓴다. 오래된 것부터 정렬하므로
                    앱은 photos 를 순서대로 넘기면 타임랩스가 된다.

                    from 과 to 는 서비스 타임존 기준 날짜(yyyy-MM-dd)이며 양쪽 모두 포함이다.
                    응답의 zoneOffset 이 그 기준이다.

                    URL 은 용도별로 세 가지다. thumbnailUrl 은 그리드, playbackUrl 은 타임랩스,
                    originalUrl 은 상세와 성장 비교용이다. 원본 30장은 수십 MB 라 재생에 쓰면
                    모바일에서 끊긴다.

                    requestedDays 와 capturedDays 를 비교하면 결측일을 알 수 있다. 로봇이 꺼져
                    있거나 촬영에 실패한 날은 사진이 없어 타임랩스가 짧아진다.

                    사진 URL 자체에는 인증이 걸리지 않는다. 경로에 UUID 두 개가 들어가 추측할 수
                    없지만 URL 이 유출되면 접근이 가능하다.
                    """
    )
    public PhotoListResponse getPhotos(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return photoService.getPhotos(principal.userId(), plantId, from, to);
    }

    @DeleteMapping("/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "성장 사진 삭제",
            description = """
                    포토 로그에서 사진 한 장을 지운다. 초점이 나갔거나 로봇이 엉뚱한 곳을 본
                    사진이 타임랩스에 남는 것을 사용자가 정리할 수 있게 한다.

                    되돌릴 수 없다. 파일과 행을 모두 지우며 생장 단계 판정 기록도 함께 사라진다.
                    대표 사진으로 걸려 있었다면 지정이 해제된다.

                    남의 식물 사진은 404 다. 존재 여부를 알려 주지 않는다.
                    """
    )
    public void deletePhoto(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @PathVariable String photoId
    ) {
        photoService.deletePhoto(principal.userId(), plantId, photoId);
    }
}
