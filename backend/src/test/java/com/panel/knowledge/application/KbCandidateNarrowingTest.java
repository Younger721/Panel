package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbCategory;
import com.panel.knowledge.domain.model.KbItemType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第一阶段：先定主题，再在主题内挑叶子。
 *
 * <p>起因：一道「数据库的存储引擎有哪些？」被模型挂到了「JVM / 内存结构与对象布局」。
 * 181 个叶子一次选一个是根源，先收窄到主题，剩下的选择才是模型擅长的。</p>
 */
class KbCandidateNarrowingTest {

    private final KbAutoClassifyService service = new KbAutoClassifyService(
            null, null, null, null, null, null);

    private static KbCategory domain(long id, String name) {
        KbCategory category = KbCategory.create(1L, 93L, name, "说明", 1);
        org.springframework.test.util.ReflectionTestUtils.setField(category, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(category, "depth", 2);
        return category;
    }

    private static final List<KbCategory> DOMAINS = List.of(
            domain(103, "JVM"),
            domain(131, "MySQL"),
            domain(140, "Redis"),
            domain(149, "消息队列"),
            domain(111, "并发编程"),
            domain(122, "Spring 生态"));

    private static KbAutoClassifyService.ItemRef item(String title, String body) {
        return new KbAutoClassifyService.ItemRef(1L, title, body, KbItemType.QUESTION, 7L);
    }

    @Test
    void 存储引擎题锁定到MySQL主题() {
        Long id = service.domainByAnchor(
                item("数据库的存储引擎有哪些？", "MySQL 常见存储引擎有 InnoDB、MyISAM、Memory。"), DOMAINS);
        assertThat(id).isEqualTo(131L);
    }

    @Test
    void 垃圾回收题锁定到JVM主题() {
        Long id = service.domainByAnchor(
                item("CMS 和 G1 有什么区别？", "CMS 关注低停顿，G1 按 Region 回收。"), DOMAINS);
        assertThat(id).isEqualTo(103L);
    }

    @Test
    void 主题词表没覆盖的题返回空由模型判断() {
        Long id = service.domainByAnchor(
                item("你最大的优点是什么？", "结合项目讲，别空泛。"), DOMAINS);
        assertThat(id).isNull();
    }

    @Test
    void 同一主题里关键词多的胜出() {
        Long id = service.domainByAnchor(
                item("Redis 的 AOF 和 RDB 怎么选？", "AOF 更安全，RDB 更快，可以混用。"), DOMAINS);
        assertThat(id).isEqualTo(140L);
    }
}
