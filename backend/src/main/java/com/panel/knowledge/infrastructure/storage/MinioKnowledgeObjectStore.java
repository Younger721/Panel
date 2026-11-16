package com.panel.knowledge.infrastructure.storage;

import com.panel.knowledge.application.KnowledgeObjectStore;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Component
@ConditionalOnProperty(name = "panel.object-store", havingValue = "minio")
public class MinioKnowledgeObjectStore implements KnowledgeObjectStore {

    private static final Logger log = LoggerFactory.getLogger(MinioKnowledgeObjectStore.class);

    private final MinioClient client;
    private final String bucket;
    /**
     * 桶是否已确认存在。
     *
     * <p>构造期只"尽力"建一次，失败不拦启动：MinIO 通常在 docker-compose 里，
     * 后端比它先起来是常态，而原来的写法会让整个应用因为一个外部服务没就绪
     * 直接 refresh 失败——连登录都用不了。真正的兜底放在每次写之前。</p>
     */
    private volatile boolean bucketReady;

    public MinioKnowledgeObjectStore(
            @Value("${panel.object-store.minio-endpoint:http://localhost:9000}") String endpoint,
            @Value("${panel.object-store.minio-access-key:minioadmin}") String accessKey,
            @Value("${panel.object-store.minio-secret-key:minioadmin}") String secretKey,
            @Value("${panel.object-store.minio-bucket:panel}") String bucket) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        ensureBucket();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (!bucketReady) {
            // 可能是启动时 MinIO 还没起来：第一次真正要写的时候再试一次
            ensureBucket();
        }
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            log.info("MinIO put ok: bucket={}, key={}, size={}", bucket, key, content.length);
        } catch (Exception ex) {
            log.error("MinIO put failed: bucket={}, key={}, size={}", bucket, key, content.length, ex);
            throw new IllegalStateException("failed to store object in MinIO", ex);
        }
    }

    @Override
    public byte[] read(String key) {
        try (InputStream stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        } catch (Exception ex) {
            // 以前这里静默返回 null，导致"对象读不出来"和"对象不存在"无法区分，
            // 排查时只能看到上层 404。保留日志但行为不变：调用方仍按缺失处理。
            //
            // 但"对象不存在"必须是 DEBUG 而不是 WARN：它每天都在正常发生
            // （软删的简历、换过存储后端的历史记录、还没生成的预览缓存 key）。
            // 实测一个列表页挂着刷新，90 秒就能刷出几十条 NoSuchKey WARN，
            // 真正的存储故障反而被埋进噪音里。
            if (isMissing(ex)) {
                log.debug("MinIO object missing: bucket={}, key={}", bucket, key);
            } else {
                log.warn("MinIO read failed: bucket={}, key={}, error={}", bucket, key, ex.toString());
            }
            return null;
        }
    }

    private static boolean isMissing(Exception ex) {
        return ex instanceof ErrorResponseException response
                && "NoSuchKey".equals(response.errorResponse().code());
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ex) {
            throw new IllegalStateException("failed to delete object from MinIO", ex);
        }
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            bucketReady = true;
        } catch (Exception ex) {
            // 构造期失败只记日志，让应用起得来；写的时候再试（见 put）
            bucketReady = false;
            log.warn("MinIO 桶检查失败（{}），上传会在首次写入时重试：{}", bucket, ex.toString());
        }
    }
}
