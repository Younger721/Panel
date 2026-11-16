package com.panel.interview.domain.model;

/**
 * 面试官人设。
 *
 * <p>一个选择同时改三件事：<b>音色</b>、<b>TTS 语气指令</b>、<b>追问阈值</b>。
 * 最后一项是重点——「压力面」不只是语气凶，而是<b>更容易追问</b>（阈值 85 比 70 更容易触发）；
 * 「亲和型」反过来，阈值 60，基本答完就过。</p>
 *
 * <p>音色都在百炼「普通系统音色」组，不占 premium 额度。</p>
 */
public enum InterviewerPersona {

    SERIOUS("严肃型", "技术面试官",
            "按简历逐点深挖，语气平稳克制，答得不扎实就会被追问。",
            "longxiaoxia_v3.1",
            "语气专业平稳，语速稍慢，吐字清晰，不带情绪起伏",
            70,
            "我是今天的面试官。接下来我会围绕你的简历提问，一共 %d 个问题，答不上来可以直接说不知道。",
            "好，我记一下。"),

    WARM("亲和型", "HR 面试官",
            "节奏放缓，多给正向反馈，适合第一次练习的人。",
            "longanhuan_v3.1",
            "语气温和亲切，语速自然，适当给予鼓励",
            60,
            "你好，很高兴认识你。今天我们先过一遍简历上的几个点，一共 %d 个问题，想到什么说什么就好，不用紧张。",
            "嗯，我了解了。"),

    PRESSURE("压力面", "资深技术专家",
            "语速偏快、不客套，同一个点会连着追三层。",
            "longanli_v3.1",
            "语气直接克制，语速正常偏快，不带客套",
            85,
            "开始吧。我会挑你简历里最不容易讲清楚的地方问，一共 %d 个问题。说不清楚就直说，别绕。",
            "行，继续。");

    private final String label;
    private final String title;
    private final String description;
    private final String voice;
    private final String instruction;
    private final int followUpThreshold;
    private final String openingTemplate;
    private final String ack;

    InterviewerPersona(String label, String title, String description, String voice, String instruction,
                       int followUpThreshold, String openingTemplate, String ack) {
        this.label = label;
        this.title = title;
        this.description = description;
        this.voice = voice;
        this.instruction = instruction;
        this.followUpThreshold = followUpThreshold;
        this.openingTemplate = openingTemplate;
        this.ack = ack;
    }

    /** 未知或为空时退回严肃型，保证老会话（没有 persona 列的行）也能正常朗读。 */
    public static InterviewerPersona fromCode(String code) {
        if (code == null || code.isBlank()) {
            return SERIOUS;
        }
        for (InterviewerPersona persona : values()) {
            if (persona.name().equalsIgnoreCase(code.trim())) {
                return persona;
            }
        }
        return SERIOUS;
    }

    /** 开场白：放在「开始面试」到第一题出现之间，盖住 Planner 那几秒的空白。 */
    public String opening(int questionCount) {
        return String.format(openingTemplate, Math.max(1, questionCount));
    }

    /** 中性承接语：用户答完后先出一声，避免几秒静默。措辞刻意不透露"下一题还是追问"。 */
    public String ack() {
        return ack;
    }

    public String label() {
        return label;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String voice() {
        return voice;
    }

    public String instruction() {
        return instruction;
    }

    public int followUpThreshold() {
        return followUpThreshold;
    }
}
