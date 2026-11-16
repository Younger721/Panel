package com.panel.audit.application;

import com.panel.ai.application.AiUsageRecord;
import com.panel.ai.application.AiUsageRepository;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.ActorAccess;
import com.panel.iam.domain.model.RoleCode;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class DefaultAiUsageQueryService implements AiUsageQueryService {
    private final AiUsageRepository repository;

    public DefaultAiUsageQueryService(AiUsageRepository repository) { this.repository = repository; }

    @Override
    public List<AiUsageRecord> list(CurrentActor actor) {
        return scoped(actor);
    }

    @Override
    public AiUsagePage search(CurrentActor actor, AiUsageFilter filter) {
        AiUsageFilter normalized = filter == null ? new AiUsageFilter(null, null, null, null, null, 0, 50) : filter;
        List<AiUsageRecord> rows = filter(scoped(actor), actor, normalized);
        int from = Math.min(rows.size(), normalized.page() * normalized.size());
        int to = Math.min(rows.size(), from + normalized.size());
        return new AiUsagePage(rows.subList(from, to), normalized.page(), normalized.size(), rows.size(), summarize(rows));
    }

    @Override
    public AiUsageSummary summary(CurrentActor actor, AiUsageFilter filter) {
        return summarize(filter(scoped(actor), actor,
                filter == null ? new AiUsageFilter(null, null, null, null, null, 0, 50) : filter));
    }

    @Override
    public List<AiUsageTrendPoint> trend(CurrentActor actor, AiUsageFilter filter) {
        List<AiUsageRecord> rows = filter(scoped(actor), actor,
                filter == null ? new AiUsageFilter(null, null, null, null, null, 0, 50) : filter);
        return rows.stream().filter(row -> row.createdAt() != null)
                .collect(Collectors.groupingBy(row -> row.createdAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> trendPoint(entry.getKey(), entry.getValue())).toList();
    }

    @Override
    public List<AiFeatureUsage> byFeature(CurrentActor actor, AiUsageFilter filter) {
        List<AiUsageRecord> rows = filter(scoped(actor), actor,
                filter == null ? new AiUsageFilter(null, null, null, null, null, 0, 50) : filter);
        return rows.stream()
                .collect(Collectors.groupingBy(
                        // feature 为空的记录来自早期版本，单独归一类，不要塞进某个真实功能里
                        row -> blank(row.feature()) ? "unknown" : row.feature()))
                .entrySet().stream()
                .map(entry -> featureUsage(entry.getKey(), entry.getValue()))
                // 成本降序：先让人看到花得最多的那个；null 成本按 0 处理，再按调用数兜底
                .sorted(Comparator.comparing(
                                (AiFeatureUsage row) -> row.estimatedCost() == null ? BigDecimal.ZERO : row.estimatedCost())
                        .reversed()
                        .thenComparing(Comparator.comparingLong(AiFeatureUsage::calls).reversed()))
                .toList();
    }

    private static AiFeatureUsage featureUsage(String feature, List<AiUsageRecord> rows) {
        AiUsageSummary summary = summarize(rows);
        return new AiFeatureUsage(feature, summary.calls(), summary.inputTokens(), summary.outputTokens(),
                summary.totalTokens(), summary.estimatedCost(), summary.averageLatencyMs(), summary.successRate());
    }

    private List<AiUsageRecord> scoped(CurrentActor actor) {
        if (actor == null || (!actor.isPlatformAdmin() && !actor.roles().contains(RoleCode.ADMIN))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Long tenantId = ActorAccess.tenantId(actor);
        return tenantId == null ? repository.findAll() : repository.findAllByTenantId(tenantId);
    }

    private List<AiUsageRecord> filter(List<AiUsageRecord> source, CurrentActor actor, AiUsageFilter filter) {
        if (filter.tenantId() != null && !actor.isPlatformAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Long scopedTenant = ActorAccess.tenantId(actor);
        if (filter.tenantId() != null && scopedTenant != null && !filter.tenantId().equals(scopedTenant)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return source.stream()
                .filter(row -> filter.tenantId() == null || Objects.equals(row.tenantId(), filter.tenantId()))
                .filter(row -> filter.from() == null || (row.createdAt() != null && !row.createdAt().isBefore(filter.from())))
                .filter(row -> filter.to() == null || (row.createdAt() != null && row.createdAt().isBefore(filter.to())))
                .filter(row -> blank(filter.model()) || filter.model().equals(row.model()))
                .filter(row -> blank(filter.status())
                        || ("UNAVAILABLE".equals(filter.status())
                        ? "UNAVAILABLE".equals(row.usageSource())
                        : filter.status().equals(row.status())))
                .sorted(Comparator.comparing(AiUsageRecord::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static AiUsageSummary summarize(List<AiUsageRecord> rows) {
        long calls = rows.size();
        long input = rows.stream().map(AiUsageRecord::inputTokens).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        long output = rows.stream().map(AiUsageRecord::outputTokens).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        long total = rows.stream().map(AiUsageRecord::totalTokens).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        BigDecimal cost = rows.stream().map(AiUsageRecord::estimatedCost).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double latency = calls == 0 ? 0 : rows.stream().mapToLong(AiUsageRecord::latencyMs).average().orElse(0);
        double success = calls == 0 ? 0 : rows.stream().filter(row -> "OK".equalsIgnoreCase(row.status())).count() * 100d / calls;
        return new AiUsageSummary(calls, input, output, total, cost, latency, success);
    }

    private static AiUsageTrendPoint trendPoint(LocalDate date, List<AiUsageRecord> rows) {
        AiUsageSummary summary = summarize(rows);
        return new AiUsageTrendPoint(date, summary.calls(), summary.inputTokens(), summary.outputTokens(),
                summary.totalTokens(), summary.estimatedCost());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
