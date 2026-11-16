package com.panel.ai.application;
import com.panel.shared.security.ActorAccess;
import com.panel.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
@Service public class DefaultAiUsageService {
    private static final Logger log = LoggerFactory.getLogger(DefaultAiUsageService.class);

    private final AiUsageRepository repository;
    private final AiModelPricingService pricing;

    public DefaultAiUsageService(AiUsageRepository repository){this(repository, null);}

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultAiUsageService(AiUsageRepository repository, AiModelPricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }
    public AiUsageRecord record(CurrentActor actor, AiUsageCommand c){
        boolean platformAdmin = actor != null && actor.isPlatformAdmin();
        Long tenantId = platformAdmin ? null : ActorAccess.tenantId(actor);
        if (!platformAdmin && tenantId == null) throw new IllegalArgumentException("tenant actor required");
        if (c == null || c.model() == null || c.model().isBlank()
                || (c.inputTokens() != null && c.inputTokens() < 0)
                || (c.outputTokens() != null && c.outputTokens() < 0)
                || (c.totalTokens() != null && c.totalTokens() < 0)
                || c.latencyMs() < 0) {
            throw new IllegalArgumentException("invalid usage");
        }
        BigDecimal cost = c.estimatedCost();
        String currency = c.currency();
        if (cost == null && pricing != null && c.inputTokens() != null && c.outputTokens() != null) {
            AiModelPricing modelPricing = pricing.find(c.provider(), c.model());
            if (modelPricing != null) {
                cost = AiUsagePricing.calculate(c.inputTokens().longValue(), c.outputTokens().longValue(),
                        modelPricing.inputPricePerMillion(), modelPricing.outputPricePerMillion());
                currency = modelPricing.currency();
            }
        }
        return repository.save(new AiUsageRecord(tenantId, actor.userId(), c.model().trim(), c.inputTokens(),
                c.outputTokens(), c.latencyMs(), c.providerRequestId(), c.status(), null, c.provider(),
                c.feature(), c.totalTokens(), c.usageSource(), cost, currency, null));
    }

    public AiUsageRecord record(CurrentActor actor, ChatModelCallResult result, String feature) {
        if (result == null) {
            return record(actor, new AiUsageCommand("unknown", (Integer) null, (Integer) null, null,
                    0, null, "ERROR", null, null, feature, "UNAVAILABLE", null, null));
        }
        ModelUsage usage = result.usage();
        String model = usage == null || usage.model() == null || usage.model().isBlank() ? "unknown" : usage.model();
        AiUsageCommand command = new AiUsageCommand(model,
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.totalTokens(),
                result.latencyMs(),
                usage == null ? null : usage.providerRequestId(),
                result.status(), null,
                usage == null ? null : usage.provider(), feature,
                usage != null && usage.available() ? "PROVIDER" : "UNAVAILABLE", null, null);
        return record(actor, command);
    }

    /**
     * 后台线程记账：没有请求上下文，但知道这份数据属于谁。
     *
     * <p>为什么需要它：简历解析跑在异步 worker 里，{@code CurrentActorProvider} 拿不到 actor，
     * 原来的实现是"跳过记账"——于是整个简历分析链路（切分 + 逐模块批注，一场至少 4 次模型调用）
     * 一条用量都不落库。结果是成本看板永远是空的，而它恰恰是调用量最大的一块。</p>
     *
     * <p>{@code tenantId} 允许为 null：{@code resume} 表只记了 owner 没记 tenant，
     * 而"少一个租户维度"远好于"整条链路不记账"。userId 是真的，随时能反查租户。</p>
     *
     * @param userId 这份数据的所有者，不能为 null（null 说明调用方搞错了，直接跳过）
     */
    public AiUsageRecord record(Long tenantId, Long userId, ChatModelCallResult result, String feature) {
        if (userId == null || result == null) {
            return null;
        }
        try {
            ModelUsage usage = result.usage();
            String model = usage == null || usage.model() == null || usage.model().isBlank()
                    ? "unknown" : usage.model();
            Integer inputTokens = usage == null ? null : usage.inputTokens();
            Integer outputTokens = usage == null ? null : usage.outputTokens();

            BigDecimal cost = null;
            String currency = null;
            if (pricing != null && inputTokens != null && outputTokens != null) {
                AiModelPricing modelPricing = pricing.find(providerOf(usage), model);
                if (modelPricing != null) {
                    cost = AiUsagePricing.calculate(inputTokens.longValue(), outputTokens.longValue(),
                            modelPricing.inputPricePerMillion(), modelPricing.outputPricePerMillion());
                    currency = modelPricing.currency();
                }
            }
            return repository.save(new AiUsageRecord(tenantId, userId, model, inputTokens, outputTokens,
                    result.latencyMs(), usage == null ? null : usage.providerRequestId(),
                    result.status(), null, providerOf(usage), feature,
                    usage == null ? null : usage.totalTokens(),
                    usage != null && usage.available() ? "PROVIDER" : "UNAVAILABLE",
                    cost, currency, null));
        } catch (RuntimeException ex) {
            // 记账是旁路：写不进去也不能让解析失败
            log.warn("后台 AI 用量记账失败（feature={}, user={}）：{}", feature, userId, ex.getMessage());
            return null;
        }
    }

    private static String providerOf(ModelUsage usage) {
        return usage == null || usage.provider() == null ? "dashscope" : usage.provider();
    }
}
