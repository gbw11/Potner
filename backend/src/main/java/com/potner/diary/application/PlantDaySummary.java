package com.potner.diary.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 일기를 쓸 근거다. 모델에게는 이 안의 것만 사실로 준다.
 *
 * <p>돌봄 기록은 <strong>식물이 겪은 일</strong>로 적는다. 일기를 쓰는 주체가 식물이라
 * "로봇이 이동했다" 는 시점이 맞지 않는다. 물을 받고 바람을 쐰 것은 식물의 하루다.
 *
 * @param alertLabels 그날 열린 이상 알림을 사람이 읽는 말로 옮긴 것. 없으면 빈 목록이다.
 * @param careLabels  그날 실제로 받은 돌봄. 명령 이력에서 나오며, 발행이 아니라 회신으로
 *                    확인된 것만 담는다 — 보내기만 하고 실패한 명령을 일기가 사실로 쓰면 안 된다.
 * @param rangeLabels 그날 온도·습도·조도가 오간 폭. 기준을 벗어나지 않아 알림이 없던 날에도
 *                    쓸 것이 남게 하는 소재다. 알림만 있으면 조용한 날 여섯 종이 모두 같은
 *                    한 줄을 각자 말투로 반복한다.
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
        List<String> careLabels,
        List<String> rangeLabels,
        String lightNote,
        boolean photoTaken
) {

    public PlantDaySummary {
        alertLabels = List.copyOf(alertLabels);
        careLabels = List.copyOf(careLabels);
        rangeLabels = List.copyOf(rangeLabels);
    }

    /** 아무 소재도 없던 날이다. 모델이 없는 사건을 지어내지 않도록 알려준다. */
    public boolean isQuietDay() {
        return alertLabels.isEmpty()
                && careLabels.isEmpty()
                && rangeLabels.isEmpty()
                && lightNote == null
                && !photoTaken;
    }
}
