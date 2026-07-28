package com.potner.diary.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlantDiaryRepository extends JpaRepository<PlantDiary, String> {

    /** 목록은 최신순이다. 사진 타임랩스와 달리 사용자는 최근 일기부터 읽는다. */
    List<PlantDiary> findAllByPlantIdAndDiaryDateBetweenOrderByDiaryDateDesc(
            String plantId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    );

    /** 상세 조회다. 식별자만으로 찾으면 남의 일기를 읽을 수 있으므로 식물까지 함께 본다. */
    Optional<PlantDiary> findByIdAndPlantId(String diaryId, String plantId);

    /** 배치가 두 번 돌았을 때 같은 날 일기를 또 쓰지 않도록 확인한다. */
    boolean existsByPlantIdAndDiaryDate(String plantId, LocalDate diaryDate);
}
