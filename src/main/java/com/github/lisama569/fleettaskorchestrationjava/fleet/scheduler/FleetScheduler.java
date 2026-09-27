package com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 后台调度循环：周期性触发 FleetService.runScheduleTick()，
 * 负责排队分配、执行超时扫描、长期排队任务的优先级老化提升。
 * tick 本身在 FleetService 的统一锁内执行，与提交/取消/回报天然互斥。
 */
public class FleetScheduler {

    private static final Logger log = LoggerFactory.getLogger(FleetScheduler.class);

    private final FleetService fleetService;
    private final FleetProperties props;
    private ScheduledExecutorService executor;

    public FleetScheduler(FleetService fleetService, FleetProperties props) {
        this.fleetService = fleetService;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (!props.isSchedulerEnabled()) {
            log.info("[scheduler] 后台调度已关闭（fleet.scheduler-enabled=false）");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fleet-scheduler");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(10L, props.getScheduleIntervalMs());
        executor.scheduleWithFixedDelay(() -> {
            try {
                fleetService.runScheduleTick();
            } catch (Throwable t) {
                // 单轮异常不能杀死调度线程
                log.warn("[scheduler] 调度轮次异常: {}", t.getMessage(), t);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        log.info("[scheduler] 后台调度已启动，间隔 {}ms", interval);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            log.info("[scheduler] 后台调度已停止");
        }
    }
}
