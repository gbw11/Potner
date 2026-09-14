package com.potner.plant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlantLifeStageRepository extends JpaRepository<PlantLifeStage, String> {

    Optional<PlantLifeStage> findByIdAndActiveTrue(String id);

    /**
     * 코드로 찾는다. 사진 판정이 쓰는 경로다.
     *
     * <p>추론 모델은 단계 식별자를 모르고 클래스 이름만 준다. 그 이름을 코드로 옮겨
     * ({@code vegetative} → {@code VEGETATIVE}) 여기서 찾는다. 식별자를 코드에 박아 두면
     * 참조 데이터를 다시 시드할 때 어긋난다.
     */
    Optional<PlantLifeStage> findByCodeAndActiveTrue(String code);
}
