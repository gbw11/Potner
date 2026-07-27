package com.potner.photo.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사진 저장 정책이다. 파일은 EC2 디스크에 두고 Nginx 가 정적으로 서빙한다.
 *
 * <p><strong>주의</strong>: {@code root} 는 배포 sync 경로 밖의 named volume 이어야 한다.
 * 배포 파이프라인이 배포 디렉토리를 매번 덮어쓰므로 그 안에 두면 배포마다 사진이 사라진다.
 * 그리고 {@code docker compose down -v} 는 이 볼륨도 지운다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.photo")
public record PhotoStorageProperties(

        /** 파일을 쓰는 디스크 경로다. */
        @NotBlank String root,

        /**
         * 앱에 내려줄 URL 의 앞부분이다. Nginx 가 {@code root} 를 이 경로로 공개한다.
         *
         * <p>기본값은 절대 URL 이 아니라 {@code /media} 다. 사진은 API 와 같은 origin 에서
         * 나가므로 앱이 자기 API base URL 에 붙이면 되고, 서버가 도메인을 알 필요가 없다.
         * 배포마다 origin 을 주입하면 값이 틀렸을 때 앱에 쓸 수 없는 URL 이 조용히 나가는데,
         * 상대 경로는 틀릴 값 자체가 없다.
         *
         * <p>사진을 다른 origin(CDN, 오브젝트 스토리지)으로 옮기면 그때 절대 URL 로 덮어쓴다.
         */
        @NotBlank String baseUrl,

        /** 타임랩스 재생용 축소본의 긴 변 픽셀이다. */
        @Positive int playbackMaxPixels,

        /** 포토 로그 그리드용 썸네일의 긴 변 픽셀이다. */
        @Positive int thumbnailMaxPixels,

        /** 업로드 허용 최대 바이트다. 넘으면 저장하지 않는다. */
        @Positive long maxUploadBytes
) {
}
