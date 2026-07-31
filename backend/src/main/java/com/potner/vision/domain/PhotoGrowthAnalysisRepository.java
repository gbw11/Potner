package com.potner.vision.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PhotoGrowthAnalysisRepository extends JpaRepository<PhotoGrowthAnalysis, String> {

    Optional<PhotoGrowthAnalysis> findByPhotoId(String photoId);

    /**
     * 이 식물의 가장 최근 <strong>확신 판정</strong>이다. 자동 개화 기록이 "새로 피었는지"를
     * 판단하는 기준으로 쓴다 — 직전 확신 판정이 이미 개화면 계속 피어 있는 것이지 새 개화가
     * 아니다.
     *
     * <p>하한 미달 판정을 제외하는 이유: 어제 신뢰도 0.4 로 개화가 찍히고 오늘 0.7 로 확정되면,
     * 미달 판정까지 세는 순간 오늘이 "이미 개화 중" 이 되어 기록이 영영 남지 않는다.
     *
     * <p>현재 사진의 판정을 저장하기 <strong>전에</strong> 조회해야 한다. 저장 후에 부르면 자기
     * 자신이 직전 판정으로 잡힌다.
     */
    Optional<PhotoGrowthAnalysis>
    findFirstByPlantIdAndDetectedStageIsNotNullAndConfidenceGreaterThanEqualOrderByAnalyzedAtDesc(
            String plantId,
            java.math.BigDecimal minConfidence
    );
}
