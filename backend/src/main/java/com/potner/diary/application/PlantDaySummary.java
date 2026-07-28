package com.potner.diary.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 일기를 쓸 근거다. 모델에게는 이 안의 것만 사실로 준다.
 *
 * <p>로봇 행동은 담기지 않는다. 서버는 이동·급수·송풍을 하지 않고 기록도 없다. 기획 자료의
 * 일기 예시가 그런 행동을 말하고 있어 모델이 지어내기 쉬운데, 근거에 없으면 프롬프트에서
 * 금지하는 것과 함께 두 겹으로 막힌다.
 *
 * @param alertLabels 그날 열린 이상 알림을 사람이 읽는 말로 옮긴 것. 없으면 빈 목록이다.
 * @param lightNote   전일 광량 판정 요약. 집계가 없거나 데이터가 부족하면 null 이다.
 * @param photoTaken  그날 장치가 사진을 남겼는지. 일기 화면이 그 사진을 함께 보여준다.
 */
public record PlantDaySummary(
        String plantNickname,
        /** 페르소나를 고르는 열쇠다. 연관을 지연 로딩하므로 트랜잭션 안에서 미리 꺼내 둔다. */
        String speciesId,
        String speciesName,
        LocalDate diaryDate,
        List<String> alertLabels,
        String lightNote,
        boolean photoTaken
) {

    public PlantDaySummary {
        alertLabels = List.copyOf(alertLabels);
    }

    /** 알림도 광량 판정도 사진도 없던 날이다. 모델이 없는 사건을 지어내지 않도록 알려준다. */
    public boolean isQuietDay() {
        return alertLabels.isEmpty() && lightNote == null && !photoTaken;
    }
}
