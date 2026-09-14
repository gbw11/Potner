package com.potner.photo.domain;

/** 한 사진의 세 가지 크기가 저장된 상대 경로다. */
public record StoredPhotoPaths(
        String originalPath,
        String playbackPath,
        String thumbnailPath
) {
}
