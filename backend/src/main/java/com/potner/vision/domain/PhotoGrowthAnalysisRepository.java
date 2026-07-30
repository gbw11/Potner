package com.potner.vision.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PhotoGrowthAnalysisRepository extends JpaRepository<PhotoGrowthAnalysis, String> {

    Optional<PhotoGrowthAnalysis> findByPhotoId(String photoId);
}
