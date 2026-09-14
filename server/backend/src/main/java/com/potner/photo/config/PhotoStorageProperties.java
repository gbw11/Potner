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
        @Positive long maxUploadBytes,

        /**
         * 장치 사진을 저장하기 전에 돌릴 각도다. 0·90·180·270 만 받는다.
         *
         * <p>카메라가 스테이션에 거꾸로 달려 있어 올라오는 사진이 180도 뒤집혀 있다. 앱에서
         * 돌리지 않고 여기서 바로잡는 이유는 화면이 네 곳(포토 로그·상세·타임랩스·성장 비교)이라
         * 표시하는 쪽에서 고치면 네 번 고쳐야 하고, 생장 단계 추론에 넘기는 이미지도 뒤집힌 채
         * 남기 때문이다.
         *
         * <p>사용자가 올리는 대표 사진에는 적용하지 않는다. 휴대폰 사진은 정방향이다.
         *
         * <p>카메라를 바로 달면 {@code PHOTO_DEVICE_ROTATE_DEGREES=0} 으로 끄면 된다. 이미
         * 저장된 사진은 다시 돌리지 않는다.
         */
        int deviceRotateDegrees,

        /**
         * 장치가 같은 날 사진을 여러 장 올릴 수 있는지다.
         *
         * <p>원래는 하루 한 장이었다. 타임랩스 프레임 간격을 일정하게 유지하기 위해서다.
         * 촬영 파이프라인을 확인하려면 하루를 기다려야 해서 허용으로 바꿨다. 자동 촬영은
         * "오늘 사진이 없으면" 조건이라 이 값과 무관하게 하루 한 번이고, 수동 촬영만 늘어난다.
         *
         * <p>원래 정책으로 되돌리려면 {@code PHOTO_ALLOW_MULTIPLE_PER_DAY=false} 다.
         */
        boolean allowMultiplePerDay
) {

    public PhotoStorageProperties {
        // 90도 단위가 아니면 이미지 크기 계산이 어긋나므로 기동 때 막는다. 사진이 저장된 뒤에
        // 발견하면 되돌릴 수 없다.
        if (Math.floorMod(deviceRotateDegrees, 90) != 0) {
            throw new IllegalArgumentException(
                    "potner.photo.device-rotate-degrees must be a multiple of 90: " + deviceRotateDegrees);
        }
    }
}
