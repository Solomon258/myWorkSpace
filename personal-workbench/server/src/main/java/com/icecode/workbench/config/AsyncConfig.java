package com.icecode.workbench.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 图片解析用的异步执行器。
 *
 * <p><b>为什么必须只有 1 个线程</b>：Hikari 池里只有 4 个连接（见 {@code DataSourceConfig}
 * 的注释：Windows 上句柄开销大，刻意压小）。解析任务每次都要写库（置 running / 写结果），
 * 而调模型那 3–10 秒里如果持有连接，几个并发解析就能把池占满，
 * 之后所有请求（包括用户正在点的列表）都会卡在获取连接上不再返回。
 * 串行化是最简单也最可预测的解法：解析慢一点无所谓，界面不能卡死。</p>
 *
 * <p>队列满时用 {@link ThreadPoolExecutor.CallerRunsPolicy}：让调用方线程自己去跑。
 * 这意味着「触发解析」这个 HTTP 请求会变成同步等待 —— 慢，但**一定不会丢任务**。
 * 相比之下 AbortPolicy 会让用户点了「解析」却什么都没发生（静默失效，本项目最忌讳的一类）。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String VISION_EXECUTOR = "visionExecutor";

    @Bean(name = VISION_EXECUTOR)
    public Executor visionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        // 线程名带前缀，出问题时 jstack / 日志里一眼能认出是谁。
        executor.setThreadNamePrefix("wb-vision-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时把队列里的任务跑完再退，否则用户刚点的解析会随着重启凭空消失。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
