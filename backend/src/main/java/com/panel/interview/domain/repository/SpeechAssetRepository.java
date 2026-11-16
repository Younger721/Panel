package com.panel.interview.domain.repository;

import com.panel.interview.domain.model.SpeechAsset;

import java.util.Optional;

/** 合成语音缓存仓储。 */
public interface SpeechAssetRepository {

    Optional<SpeechAsset> findByCacheKey(String cacheKey);

    SpeechAsset save(SpeechAsset asset);
}
