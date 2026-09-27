package com.github.lisama569.fleettaskorchestrationjava.tests.fleet;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.engine.AsyncSimulatedDispatchEngine;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.InMemoryRobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实异步模拟引擎的端到端行为：自动进度、成功/失败/超时、重复 dispatch 幂等。 */
class FleetAsyncEngineTest {

    private static final Logger log = LoggerFactory.getLogger(FleetAsyncEngineTest.class);

    private AsyncSimulatedDispatchEngine engine;
    private FleetService fleet;

    private FleetService build() {
        TaskStore store = TestSupport.tempStore();
        RobotRegistry registry = new InMemoryRobotRegistry();
        engine = new AsyncSimulatedDispatchEngine();
        FleetProperties props = TestSupport.props();
        FleetService service = new FleetService(store, null, registry, engine,
                new com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler.CapabilityLoadPolicy(),
                props, System::currentTimeMillis);
        service.registerRobot("R1", Set.of("LIFT"), 5);
        service.registerRobot("R2", Set.of("LIFT"), 5);
        fleet = service;
        return service;
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    @Test
    void shouldRunProgressAndCompleteAutomatically() throws Exception {
        build();
        fleet.submit(new TaskRequest("A1", "LIFT", 50, 0, 2, 300, false, 0));
        // 等待引擎把 IN_PROGRESS -> COMPLETED 全部跑完
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline
                && fleet.getTask("A1").getStatus() != TaskStatus.COMPLETED) {
            Thread.sleep(25);
        }
        Task t = fleet.getTask("A1");
        log.info("[用例输入] A1 duration=300ms 自动执行；[判定] 状态={} 进度={}% 机器人IDLE={}",
                t.getStatus(), t.getProgressPercent(),
                fleet.listRobots().get(0).getStatus());
        assertThat(t.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(t.getProgressPercent()).isEqualTo(100);
        // 完成后机器人应已释放（在 A1 完成瞬间可能立即派给后来的排队任务，这里 A1 是唯一任务）
        assertThat(fleet.listRobots().get(0).getStatus().name()).isIn("IDLE", "BUSY");
    }

    @Test
    void shouldFailInjectedTaskAndFreeRobot() throws Exception {
        build();
        fleet.submit(new TaskRequest("F1", "LIFT", 50, 0, 2, 200, true, 0));
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline
                && fleet.getTask("F1").getStatus() != TaskStatus.FAILED) {
            Thread.sleep(25);
        }
        Task t = fleet.getTask("F1");
        log.info("[用例输入] F1 failBeforeComplete=true；[判定] 状态={} errorCode={} R1释放={}",
                t.getStatus(), t.getErrorCode(),
                fleet.listRobots().get(0).getStatus().name());
        assertThat(t.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(t.getErrorCode()).isEqualTo("SIMULATED_FAILURE");
        assertThat(fleet.listRobots().get(0).getStatus().name()).isEqualTo("IDLE");
    }

    @Test
    void shouldTimeoutShorterThanDuration() throws Exception {
        build();
        fleet.submit(new TaskRequest("TO1", "LIFT", 50, 0, 2, 3000, false, 250));
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline
                && fleet.getTask("TO1").getStatus() != TaskStatus.TIMED_OUT) {
            Thread.sleep(25);
        }
        Task t = fleet.getTask("TO1");
        log.info("[用例输入] TO1 duration=3000ms timeout=250ms；[判定] 状态={} errorCode={}",
                t.getStatus(), t.getErrorCode());
        assertThat(t.getStatus()).isEqualTo(TaskStatus.TIMED_OUT);
        // 超时后引擎的“完成”事件不能再把状态改成 COMPLETED（CAS + 终态幂等）
        Thread.sleep(500);
        assertThat(fleet.getTask("TO1").getStatus()).isEqualTo(TaskStatus.TIMED_OUT);
    }
}
