package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbItemType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 归类守卫：模型串域时要打折，别让「数据库的存储引擎」挂到「JVM / 内存结构与对象布局」。
 *
 * <p>这条规则的来由是真实事故：同一道题，模型一次挂 JVM（0.72，刚好够自动挂），
 * 隔几分钟再问又能挂到 MySQL 下（0.86）。采样会抖，兜底必须是确定性的。</p>
 */
class KbClassifyGuardTest {

    private final KbAutoClassifyService service = new KbAutoClassifyService(
            null, null, null, null, null, null);

    private static KbAutoClassifyService.ItemRef item(String title, String body) {
        return new KbAutoClassifyService.ItemRef(1L, title, body, KbItemType.QUESTION, 7L);
    }

    @Test
    void 存储引擎题挂到JVM要打折() {
        KbAutoClassifyService.ItemRef question = item(
                "数据库的存储引擎有哪些？",
                "MySQL 常见存储引擎有 InnoDB、MyISAM、Memory、CSV 和 Archive。");
        double guarded = service.guardConfidence(question, "后端/jvm/内存结构与对象布局", 0.72);
        assertThat(guarded).isEqualTo(0.36);
    }

    @Test
    void 存储引擎题挂到MySQL不打折() {
        KbAutoClassifyService.ItemRef question = item(
                "数据库的存储引擎有哪些？",
                "MySQL 常见存储引擎有 InnoDB、MyISAM、Memory、CSV 和 Archive。");
        assertThat(service.guardConfidence(question, "后端/mysql/存储引擎", 0.72)).isEqualTo(0.72);
    }

    @Test
    void 垃圾回收题挂到MySQL要打折() {
        KbAutoClassifyService.ItemRef question = item(
                "CMS 和 G1 有什么区别？",
                "CMS 关注低停顿，G1 按 Region 回收，JDK9 默认。");
        assertThat(service.guardConfidence(question, "后端/mysql/慢查询与优化", 0.9)).isEqualTo(0.45);
    }

    @Test
    void 没有主题锚词的题不受影响() {
        KbAutoClassifyService.ItemRef question = item("你最大的优点是什么？", "结合项目讲，别空泛。");
        assertThat(service.guardConfidence(question, "后端/jvm/内存结构与对象布局", 0.8)).isEqualTo(0.8);
    }

    @Test
    void 同时命中多个主题时只要路径沾上一个就放行() {
        KbAutoClassifyService.ItemRef question = item(
                "MySQL 的事务隔离和 Redis 的原子性有什么区别？",
                "InnoDB 用 MVCC，Redis 用单线程加 Lua。");
        assertThat(service.guardConfidence(question, "后端/redis/持久化rdb/aof", 0.85)).isEqualTo(0.85);
    }
}
