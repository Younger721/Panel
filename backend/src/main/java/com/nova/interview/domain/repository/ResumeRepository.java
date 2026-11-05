package com.nova.interview.domain.repository;

import com.nova.interview.domain.model.Resume;

import java.util.List;
import java.util.Optional;

/** 简历原文仓储（单租户，不按租户过滤）。 */
public interface ResumeRepository {

    Resume save(Resume resume);

    Optional<Resume> findById(Long id);

    /** 最近 50 份，列表页用。 */
    List<Resume> listRecent();

    /** 同内容去重：同样的文件重复上传直接复用原记录。 */
    Optional<Resume> findByContentHash(String contentHash);
}
