package com.panel.knowledge.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * 知识点能力模型：简化的 Elo / IRT 单参数版。
 *
 * <p>一个知识点一个能力值 {@code theta}，一道题一个难度 {@code b}，
 * 预期答对概率 {@code P = 1 / (1 + exp(-(theta - b)))}。观测到结果后按
 * {@code theta += weight * K * (observed - P)} 移动：</p>
 *
 * <ul>
 *   <li>答对一道"本来就该会"的题（P 接近 1）几乎不加分 —— 不算新信息；</li>
 *   <li>答错一道"不该错"的题（P 接近 1）扣很多 —— 这才是真弱项；</li>
 *   <li>{@code K} 随作答次数递减，做得越多每题的冲击越小，避免一两题翻盘；</li>
 *   <li>长期不做，能力值向 0 回落（忘了），但最多只回落到 60%，不会把已证明的强项抹平。</li>
 * </ul>
 *
 * <p>纯函数，不碰数据库，方便单测。</p>
 */
public final class KbSkillModel {

    /** 初始学习率。 */
    public static final double K0 = 0.6;
    /** 每多做一次，学习率衰减 15%：{@code K = K0 / (1 + 0.15 * attempts)}。 */
    public static final double K_DECAY_PER_ATTEMPT = 0.15;
    /** 遗忘半衰期（天）。 */
    public static final double TAU_DAYS = 30.0;
    /** 衰减下限：最多回落到原值的 60%。 */
    public static final double DECAY_FLOOR = 0.6;
    /** 面试信号的权重倍数：高压真实场景，一次顶两次刷题。 */
    public static final double INTERVIEW_WEIGHT = 2.0;
    /** "不确定"按半对处理，且只给一半权重 —— 既有信息，又不算答对。 */
    public static final double UNSURE_OBSERVATION = 0.5;
    public static final double UNSURE_WEIGHT = 0.5;
    /** 难度区间，对应 1~5 星。 */
    public static final double MIN_B = -2.4;
    public static final double MAX_B = 2.4;

    private KbSkillModel() {
    }

    /**
     * 题目难度 → 难度参数 b（-2.4 ~ 2.4）。
     *
     * <p>{@code kb_question.difficulty} 是自由文本（"中"/"高"/"3"/"HARD" 都见过），
     * 所以按"能认出来就认，认不出来按中等"处理，绝不因为脏数据抛异常。</p>
     */
    public static double difficultyB(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0d;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        Double numeric = numeric(value);
        if (numeric != null) {
            return clamp((numeric - 3d) * 1.2d, MIN_B, MAX_B);
        }
        if (containsAny(value, "极", "very", "expert", "5")) {
            return 1.8d;
        }
        if (containsAny(value, "难", "hard", "高", "difficult", "senior", "adv")) {
            return 1.2d;
        }
        if (containsAny(value, "中", "medium", "mid", "normal", "一般")) {
            return 0d;
        }
        if (containsAny(value, "简", "易", "easy", "低", "basic", "junior", "入门")) {
            return -1.2d;
        }
        return 0d;
    }

    /** 学习率：做得越多越稳。 */
    public static double kFactor(int attempts) {
        return K0 / (1d + K_DECAY_PER_ATTEMPT * Math.max(0, attempts));
    }

    /** 预期答对概率。 */
    public static double expected(double theta, double b) {
        return 1d / (1d + Math.exp(-(theta - b)));
    }

    /** 一次观测之后的新能力值。 */
    public static double nextTheta(double theta, double b, double observation, double weight, int attempts) {
        double k = kFactor(attempts) * Math.max(0d, weight);
        return theta + k * (observation - expected(theta, b));
    }

    /** 自评结果 → 观测值（0~1）。 */
    public static double observationOf(String result) {
        if (result == null) {
            return UNSURE_OBSERVATION;
        }
        return switch (result.trim().toUpperCase(Locale.ROOT)) {
            case "CORRECT" -> 1d;
            case "WRONG" -> 0d;
            case "UNSURE" -> UNSURE_OBSERVATION;
            default -> UNSURE_OBSERVATION;
        };
    }

    /** 结果 + 来源 → 权重（面试 2 倍；不确定半权重）。 */
    public static double weightOf(String result, boolean interview) {
        double weight = interview ? INTERVIEW_WEIGHT : 1d;
        if (result != null && "UNSURE".equalsIgnoreCase(result.trim())) {
            weight *= UNSURE_WEIGHT;
        }
        return weight;
    }

    /**
     * 时间衰减后的能力值：久不练就向 0 回落（"不确定了"），但最多只回落 40%。
     * 注意 0 是"平均水平"，所以正负都朝 0 收，语义是"越来越不确定"，不是"变强"。
     */
    public static double effectiveTheta(double theta, Instant lastSeen, Instant now) {
        if (lastSeen == null || now == null) {
            return theta;
        }
        double days = Duration.between(lastSeen, now).toSeconds() / 86_400d;
        if (days <= 0) {
            return theta;
        }
        double factor = Math.max(DECAY_FLOOR, Math.exp(-days / TAU_DAYS));
        return theta * factor;
    }

    /** 下次答错的概率估计（把难度按"中等"算），给用户看的可解释数字。 */
    public static double wrongRate(double thetaEffective, double b) {
        return 1d - expected(thetaEffective, b);
    }

    private static Double numeric(String value) {
        try {
            return Double.valueOf(value.replace("星", "").replace("level", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
