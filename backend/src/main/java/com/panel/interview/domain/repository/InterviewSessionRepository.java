package com.panel.interview.domain.repository;

import com.panel.interview.domain.model.InterviewSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 面试会话仓储。 */
public interface InterviewSessionRepository {

    InterviewSession save(InterviewSession session);

    Optional<InterviewSession> findById(Long id);

    /**
     * 该用户最近一场还没结束的面试。
     *
     * <p>用来把「连点开始面试」变成幂等：没有这个查询时，用户点两下就是两场会话、
     * 四次模型调用（JobFit + Planner 各两次），而其中一场注定被丢掉。</p>
     */
    Optional<InterviewSession> findLatestInProgress(Long ownerUserId);

    /** 历史记录：按创建时间倒序（管理员视角，跨用户）。 */
    List<InterviewSession> findAll();

    /** 某个用户的历史记录：按创建时间倒序。 */
    List<InterviewSession> findAllByOwner(Long ownerUserId);

    /** 卡在"进行中"且长时间没有推进的会话，交给定时任务收尾。 */
    List<InterviewSession> findStaleInProgress(Instant idleBefore);

    /**
     * 仍然进行中、且仍然空闲够久，才置为放弃（原子条件更新）。
     *
     * @return 影响行数：0 表示这期间用户又推进了，不该收尾
     */
    int abandonIfIdle(Long id, Instant idleBefore);
}
