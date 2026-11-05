package com.nova.knowledge.infrastructure.storage;

import com.nova.knowledge.application.KnowledgeObjectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "nova.object-store", havingValue = "local", matchIfMissing = true)
public class LocalKnowledgeObjectStore implements KnowledgeObjectStore {

    private final Path root;

    public LocalKnowledgeObjectStore(@Value("${nova.object-store.local-dir:${java.io.tmpdir}/nova-knowledge}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (key == null || key.isBlank() || content == null) {
            throw new IllegalArgumentException("invalid object");
        }
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to store object", ex);
        }
    }

    @Override
    public byte[] read(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Path target = resolve(key);
        try {
            return Files.exists(target) ? Files.readAllBytes(target) : null;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read object", ex);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to delete object", ex);
        }
    }

    private Path resolve(String key) {
        Path normalized = root.resolve(key.replace('\\', '/')).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("invalid object key");
        }
        return normalized;
    }
}
