package com.panel.shared.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.Callable;

/**
 * 把当前请求的 {@link SecurityContext} 带进子线程。
 *
 * <p>{@code SecurityContextHolder} 默认是 ThreadLocal，虚拟线程不继承它。面试的两位评委
 * 是并行跑的，而每次模型调用都要按 actor 记用量账——上下文丢了，账就记到了空气里。
 * 任务结束立刻 clear，避免上下文残留到被复用的线程上。</p>
 */
public final class SecurityContextPropagation {

    private SecurityContextPropagation() {
    }

    public static Runnable wrap(Runnable task) {
        SecurityContext context = SecurityContextHolder.getContext();
        return () -> {
            SecurityContextHolder.setContext(context);
            try {
                task.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        SecurityContext context = SecurityContextHolder.getContext();
        return () -> {
            SecurityContextHolder.setContext(context);
            try {
                return task.call();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
