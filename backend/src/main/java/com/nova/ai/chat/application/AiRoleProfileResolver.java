package com.nova.ai.chat.application;

import com.nova.iam.domain.model.RoleCode;
import com.nova.shared.security.ActorAccess;
import com.nova.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色画像：决定 AI 助手的自我定位、示例问题与可用插件。
 *
 * <p>NexusAI 是求职者平台，因此只保留三类业务角色：
 * 租户管理员（招聘运营）、面试官（内部）、求职者（C 端）。</p>
 */
@Component
public class AiRoleProfileResolver {

    private static final List<String> PLATFORM_SAMPLES = List.of(
            "全平台本月 AI 调用了多少次？",
            "有多少租户启用了 AI？",
            "现在有多少活跃租户？",
            "平台一共有多少租户？");

    private static final List<String> PLATFORM_TENANT_SAMPLES = List.of(
            "当前租户本月 AI 配额和用量是多少？",
            "当前租户是否已启用 AI？",
            "当前租户的智能体和知识库配额用了多少？");

    private static final List<String> ADMIN_SAMPLES = List.of(
            "本月一共进行了多少场模拟面试？",
            "简历库现在有多少份已解析的简历？",
            "哪些岗位的匹配通过率最低？",
            "最近一周评估分数分布怎么样？");

    /** 各角色通用联网工具：web_search（联网搜索）+ web_fetch（读取网页 URL 正文） */
    private static final List<String> INTERVIEWER_PLUGINS = List.of(
            "basic_chat", "knowledge_search", "ai_memory",
            "web_search", "web_fetch");

    private static final List<String> CANDIDATE_PLUGINS = List.of(
            "basic_chat", "knowledge_search", "ai_memory",
            "web_search", "web_fetch");

    public AiRoleProfile resolve(CurrentActor actor) {
        RoleCode role = actor.isPlatformAdmin() ? RoleCode.PLATFORM_ADMIN
                : actor.roles().contains(RoleCode.ADMIN) ? RoleCode.ADMIN
                : actor.roles().contains(RoleCode.INTERVIEWER) ? RoleCode.INTERVIEWER : RoleCode.CANDIDATE;
        return switch (role) {
            case PLATFORM_ADMIN -> resolvePlatformAdmin(actor);
            case ADMIN -> new AiRoleProfile("ADMIN", "招聘运营助手",
                    "你是招聘运营助手，只回答当前租户的简历库、岗位、面试场次与评估统计问题，不得访问其他租户。"
                            + "无法从知识库或已有数据得到的数字，直接说明没有数据，不要推测。",
                    ADMIN_SAMPLES, INTERVIEWER_PLUGINS, 10);
            case INTERVIEWER -> new AiRoleProfile("INTERVIEWER", "面试官工作助手",
                    "你是面试官工作助手，可协助出题、追问设计、评分口径校准以及候选人横向对比。"
                            + "评分必须给出可核查的依据；没有候选人授权时不要透露其他候选人的简历内容。",
                    List.of("帮我针对 Java 并发出一道追问", "这道回答按 rubric 能打几分？",
                            "候选人说做过分库分表，追问他什么？", "对比一下这两个候选人的技术深度"),
                    INTERVIEWER_PLUGINS, 10);
            case CANDIDATE -> new AiRoleProfile("CANDIDATE", "我的求职助手",
                    "你是求职助手，只能使用当前用户自己的简历、目标岗位与面试记录，提供简历优化、岗位匹配、"
                            + "技术知识答疑和模拟面试训练建议。不要推测或查看其他人的数据。",
                    List.of("我的简历和目标岗位差在哪？", "帮我准备一轮 Java 后端模拟面试",
                            "MySQL 索引失效的常见场景有哪些？", "上次面试评估说我表达逻辑弱，怎么改？"),
                    CANDIDATE_PLUGINS, 10);
        };
    }

    /** 平台管理员始终走平台 AI 问答，顶栏选中租户只补充该租户的配额类问题。 */
    private static AiRoleProfile resolvePlatformAdmin(CurrentActor actor) {
        List<String> samples = new ArrayList<>(PLATFORM_SAMPLES);
        String tenantHint = "";
        if (ActorAccess.tenantId(actor) != null) {
            samples.addAll(PLATFORM_TENANT_SAMPLES);
            tenantHint = "顶栏已选租户时，该租户的 AI 启用状态、配额与本月用量同样按租户维度作答，不要改问简历或面试明细。";
        }
        return new AiRoleProfile("PLATFORM_ADMIN", "平台 AI 管理助手",
                "你是平台 AI 管理助手，只回答平台租户、AI 配额、调用统计和平台配置问题。"
                        + "没有对应工具时按知识库与已有数据回答，不要向用户索要 tenantId 来替代查询。"
                        + tenantHint
                        + "不要回答单个候选人的简历、面试题目和评估细节。",
                List.copyOf(samples),
                List.of("basic_chat", "knowledge_search"),
                10);
    }
}
