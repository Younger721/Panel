package com.panel.interview.infrastructure.persistence;

import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface SpringDataInterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    /**
     * 历史列表带上限。
     *
     * <p>原来是 findAll()：用户攒到几百场之后，列表页一次性把全部会话（每场都含
     * report_json 摘要解析）拉出来。列表只需要"最近这些"，没有上限就是等它慢慢变慢。</p>
     */
    List<InterviewSession> findTop200ByOrderByCreatedAtDesc();

    List<InterviewSession> findTop200ByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    Optional<InterviewSession> findFirstByOwnerUserIdAndStatusOrderByIdDesc(Long ownerUserId, InterviewStatus status);

    List<InterviewSession> findAllByStatusAndUpdatedAtBefore(InterviewStatus status, Instant updatedAt);

    /**
     * 条件窄更新：仍然 IN_PROGRESS 且确实空闲够久，才置为 ABANDONED。
     *
     * <p>不用「读实体 → 改 → save」那一套：定时任务扫到超时会话之后、写回去之前，
     * 用户完全可能刚好提交下一题的答案。窄更新把「还在进行中」「仍然够旧」两个条件
     * 放进同一条 SQL，判断与写入不可分割，不会把活跃会话判成已放弃。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InterviewSession s
               set s.status = :target, s.updatedAt = :now
             where s.id = :id
               and s.status = :current
               and s.updatedAt < :idleBefore
            """)
    int abandonIfIdle(@Param("id") Long id,
                      @Param("current") InterviewStatus current,
                      @Param("target") InterviewStatus target,
                      @Param("idleBefore") Instant idleBefore,
                      @Param("now") Instant now);
}
