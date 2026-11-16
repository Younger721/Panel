package com.panel.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 并行评分要按 actor 记用量账：上下文必须真的能带进子线程，并且用完不残留。 */
class SecurityContextPropagationTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void carriesCallerContextIntoAnotherThread() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        Callable<String> task = SecurityContextPropagation.wrap(() ->
                String.valueOf(SecurityContextHolder.getContext().getAuthentication().getName()));

        AtomicReference<String> seen = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                seen.set(task.call());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        worker.start();
        worker.join();

        assertThat(seen.get()).isEqualTo("alice");
    }

    @Test
    void clearsContextOnTheWorkerAfterRun() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("bob", "n/a", List.of()));

        AtomicReference<Authentication> leftover = new AtomicReference<>();
        SecurityContextPropagation.wrap(() -> leftover.set(
                SecurityContextHolder.getContext().getAuthentication())).run();

        // 任务内部看得到调用方身份，跑完必须清掉，避免残留到被复用的线程上
        assertThat(leftover.get()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
