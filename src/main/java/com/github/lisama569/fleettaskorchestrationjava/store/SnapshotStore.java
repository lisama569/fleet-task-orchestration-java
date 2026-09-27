package com.github.lisama569.fleettaskorchestrationjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.fleettaskorchestrationjava.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.service.FleetLock;
import com.github.lisama569.fleettaskorchestrationjava.service.Scheduler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 本地文件快照：每次持锁变更后写盘（写时替换，原子移动），
 * 启动时加载并把未完成任务恢复为可调度状态，模拟"服务重启不丢任务、不重复下发"。
 */
@Component
public class SnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final TaskStore taskStore;
    private final RobotStore robotStore;
    private final FleetLock fleetLock;
    private final Scheduler scheduler;
    private final Path snapshotFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotStore(TaskStore taskStore, RobotStore robotStore, FleetLock fleetLock,
                         Scheduler scheduler,
                         @Value("${fleet.snapshot-file:fleet-state.json}") String snapshotFile) {
        this.taskStore = taskStore;
        this.robotStore = robotStore;
        this.fleetLock = fleetLock;
        this.scheduler = scheduler;
        this.snapshotFile = Path.of(snapshotFile);
    }

    @PostConstruct
    public void init() {
        load();
        // 恢复：把中断的 ASSIGNED/RUNNING 任务回退为 PENDING，机器人复位
        scheduler.recoverOnStartup();
        // 每次持锁变更后自动落盘
        fleetLock.setAfterMutation(this::saveQuietly);
        scheduler.scheduleOnce();
    }

    /** 从快照文件恢复任务与机器人；文件不存在则空启动。 */
    public void load() {
        if (!Files.exists(snapshotFile)) {
            log.info("[snapshot] no snapshot file {}, starting empty", snapshotFile);
            return;
        }
        try {
            Snapshot snap = mapper.readValue(snapshotFile.toFile(), Snapshot.class);
            for (TaskData d : snap.tasks()) {
                taskStore.putIfAbsent(toTask(d));
            }
            for (RobotData d : snap.robots()) {
                robotStore.putIfAbsent(toRobot(d));
            }
            log.info("[snapshot] loaded {} tasks, {} robots from {}",
                    snap.tasks().size(), snap.robots().size(), snapshotFile);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load snapshot " + snapshotFile, e);
        }
    }

    /** 把当前内存状态写盘（先写临时文件再原子移动，避免半截文件）。 */
    public void save() {
        Snapshot snap = new Snapshot(
                taskStore.all().stream().map(SnapshotStore::toTaskData).toList(),
                robotStore.all().stream().map(SnapshotStore::toRobotData).toList());
        try {
            Path tmp = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snap);
            Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save snapshot " + snapshotFile, e);
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (RuntimeException e) {
            log.warn("[snapshot] save failed: {}", e.getMessage());
        }
    }

    // ---------- DTO 与实体互转 ----------

    public record Snapshot(List<TaskData> tasks, List<RobotData> robots) {
    }

    public record TaskData(String taskId, int basePriority, Long deadline, String requiredCapability,
                           int requiredLoad, long timeoutMillis, Long submittedAt, TaskStatus status,
                           String assignedRobotId, long attemptId, Long startedAt, Long finishedAt,
                           String statusReason, int priorityBoosts) {
    }

    public record RobotData(String robotId, Set<String> capabilities, int maxLoad,
                            RobotStatus status, String currentTaskId) {
    }

    private static TaskData toTaskData(Task t) {
        return new TaskData(t.getTaskId(), t.getBasePriority(), toEpoch(t.getDeadline()),
                t.getRequiredCapability(), t.getRequiredLoad(), t.getTimeoutMillis(),
                toEpoch(t.getSubmittedAt()), t.getStatus(), t.getAssignedRobotId(), t.getAttemptId(),
                toEpoch(t.getStartedAt()), toEpoch(t.getFinishedAt()), t.getStatusReason(),
                t.getPriorityBoosts());
    }

    private static RobotData toRobotData(Robot r) {
        return new RobotData(r.getRobotId(), r.getCapabilities(), r.getMaxLoad(),
                r.getStatus(), r.getCurrentTaskId());
    }

    private static Task toTask(TaskData d) {
        Task t = new Task(d.taskId(), d.basePriority(), toInstant(d.deadline()), d.requiredCapability(),
                d.requiredLoad(), d.timeoutMillis(),
                d.submittedAt() != null ? Instant.ofEpochMilli(d.submittedAt()) : Instant.now());
        t.setStatus(d.status());
        t.setAssignedRobotId(d.assignedRobotId());
        t.setAttemptId(d.attemptId());
        t.setStartedAt(toInstant(d.startedAt()));
        t.setFinishedAt(toInstant(d.finishedAt()));
        t.setStatusReason(d.statusReason());
        t.setPriorityBoosts(d.priorityBoosts());
        return t;
    }

    private static Robot toRobot(RobotData d) {
        Robot r = new Robot(d.robotId(), d.capabilities(), d.maxLoad());
        r.setStatus(d.status());
        r.setCurrentTaskId(d.currentTaskId());
        return r;
    }

    private static Long toEpoch(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant toInstant(Long epoch) {
        return epoch == null ? null : Instant.ofEpochMilli(epoch);
    }
}
