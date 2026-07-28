package com.potner.photo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 포토 로그에 이미 있는 사진을 대표로 고를 때 쓴다. 새로 올리는 것은 multipart 경로다. */
public record SelectRepresentativePhotoRequest(
        @NotBlank(message = "사진 식별자는 필수입니다.")
        @Size(max = 36, message = "사진 식별자가 유효하지 않습니다.")
        String photoId
) {
}
