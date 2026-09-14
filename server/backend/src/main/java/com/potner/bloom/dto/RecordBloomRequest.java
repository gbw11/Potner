package com.potner.bloom.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RecordBloomRequest(

        /**
         * 꽃이 핀 날이다. 비우면 서버가 서비스 타임존 기준 오늘로 채운다.
         *
         * <p>보통은 비운 채로 온다. 사용자가 꽃을 본 순간에 누르기 때문이다. 며칠 지나서
         * 기록하는 경우를 위해 받아 두되, 서버가 아는 오늘보다 미래면 거부한다. 날짜는
         * {@code plant_photo.photo_date} 와 같은 서비스 타임존 기준이라 앱과 서버가 같은
         * '오늘'을 본다.
         */
        LocalDate bloomDate,

        @Size(max = 200, message = "메모는 200자 이하여야 합니다.")
        String note
) {
}
