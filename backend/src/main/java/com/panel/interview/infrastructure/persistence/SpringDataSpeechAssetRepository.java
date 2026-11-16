package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.SpeechAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataSpeechAssetRepository extends JpaRepository<SpeechAsset, Long> {

    Optional<SpeechAsset> findByCacheKey(String cacheKey);
}
