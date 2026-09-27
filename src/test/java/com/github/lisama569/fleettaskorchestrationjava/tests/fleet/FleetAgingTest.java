package com.github.lisama569.fleettaskorchestrationjava.tests.fleet;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.InMemoryRobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 长期排队任务的优先级老化提升测试。 */
class FleetAgingTest {

    private static final Logger log = LoggerFactory.getLogger(FleetAgingTest.class);

    private TaskStore store;
    private RobotRegistry registry;
    private TestSupport.NoopEngine engine;
    private FleetService fleet;
    private FleetProperties props;
    private final AtomicLong clock = new AtomicLong(10_000L);

    @BeforeEach
    void setUp() {
        store = TestSupport.tempStore();
        registry = new InMemoryRobotRegistry();
        engine = new TestSupport.NoopEngine();
        props = TestSupport.props();
        props.setAgingAfterMs(500L);   // 排队超过 500ms 开始老化
        props.setAgingBoost(50);        // 每次提升 50
        props.setAgingIntervalMs(200L); // 每 200ms 可再提升一次
        fleet = TestSupport.newService(store, registry, engine, props, clock);
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
    }

    private TaskRequest task(String id, int pri) {
        return new TaskRequest(id, "LIFT", pri, 0, 2, 1000, false, 0);
    }

    @Test
    void shouldBoostLongWaitingTaskAndLetItJumpQueue() {
        // 时间轴（可控时钟）：
        // 10000 HOLDER 提交并占住 R1
        // 10100 OLD(pri10) 提交
        // 10500 EARLY(pri80) 提交
        fleet.submit(task("HOLDER", 5));
        clock.addAndGet(100);
        fleet.submit(task("OLD", 10));
        clock.addAndGet(400);
        fleet.submit(task("EARLY", 80));
        assertThat(clock.get()).isEqualTo(10_500L);

        // 提升规则：排队超过 500ms 后首次 +50；超出部分每满 200ms 再 +50
        // tick @10600: OLD 排队500 -> 首次 +50=60；EARLY 排队100 不提升
        // tick @10700: OLD overtime100 仍 60
        // tick @10800: OLD overtime200 -> 再 +50=110；EARLY 排队300 不提升
        clock.addAndGet(100); fleet.runScheduleTick(); // 10600
        clock.addAndGet(100); fleet.runScheduleTick(); // 10700
        clock.addAndGet(100); fleet.runScheduleTick(); // 10800

        int oldEff = fleet.getTask("OLD").effectivePriority();
        int earlyEff = fleet.getTask("EARLY").effectivePriority();
        log.info("[用例输入] OLD(pri10,排队700ms,两次提升=110) / EARLY(pri80,排队300ms,未超500阈值=80)；[判定] OLD有效优先级={} EARLY有效优先级={}",
                oldEff, earlyEff);
        assertThat(oldEff).isEqualTo(110);
        assertThat(earlyEff).isEqualTo(80);

        // HOLDER 完成的同一轮调度：已老化的 OLD(110) 先于 EARLY(80) 抢到 R1
        fleet.reportSuccess("HOLDER", "R1");
        log.info("[用例输入] HOLDER 完成；[判定] OLD归属={} 状态={}；EARLY状态={}",
                fleet.getTask("OLD").getAssignedRobotId(), fleet.getTask("OLD").getStatus(),
                fleet.getTask("EARLY").getStatus());
        assertThat(fleet.getTask("OLD").getAssignedRobotId()).isEqualTo("R1");
        assertThat(fleet.getTask("OLD").getStatus()).isEqualTo(TaskStatus.ALLOCATED);
        assertThat(fleet.getTask("EARLY").getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void shouldNotBoostWhenAgingDisabled() {
        props.setAgingAfterMs(0L);
        fleet.submit(task("T1", 10));
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(2_000L);
            fleet.runScheduleTick();
        }
        log.info("[用例输入] 老化关闭，T1 排队很久；[判定] 有效优先级={}（应仍为10）",
                fleet.getTask("T1").effectivePriority());
        assertThat(fleet.getTask("T1").effectivePriority()).isEqualTo(10);
    }
}
