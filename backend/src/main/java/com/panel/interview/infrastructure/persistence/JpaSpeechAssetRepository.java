package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.SpeechAsset;
import com.panel.interview.domain.repository.SpeechAssetRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaSpeechAssetRepository implements SpeechAssetRepository {

    private final SpringDataSpeechAssetRepository delegate;

    public JpaSpeechAssetRepository(SpringDataSpeechAssetRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<SpeechAsset> findByCacheKey(String cacheKey) {
        return delegate.findByCacheKey(cacheKey);
    }

    @Override
    public SpeechAsset save(SpeechAsset asset) {
        return delegate.save(asset);
    }
}
