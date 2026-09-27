package com.github.lisama569.fleettaskorchestrationjava.fleet.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 基于本地 JSON 文件的任务存储。
 * 写入采用“写临时文件 + 原子改名”，保证进程中断时不会留下半截文件；
 * 重启时 loadAll 把未完成任务恢复出来。
 */
public class JsonFileTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileTaskStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileTaskStore(String path) {
        this.file = Path.of(path);
    }

    @Override
    public synchronized void saveAll(Collection<Task> tasks) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            List<TaskRow> rows = tasks.stream().map(TaskRow::from).toList();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), rows);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("持久化任务失败: " + file, e);
        }
    }

    @Override
    public synchronized List<Task> loadAll() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<TaskRow> rows = mapper.readerForListOf(TaskRow.class).readValue(file.toFile());
            List<Task> tasks = new ArrayList<>(rows.size());
            for (TaskRow row : rows) {
                tasks.add(row.toTask());
            }
            log.info("[store] 从 {} 加载任务 {} 个", file, tasks.size());
            return tasks;
        } catch (IOException e) {
            throw new UncheckedIOException("读取任务存储失败: " + file, e);
        }
    }

    /** 持久化行对象：与 Task 全字段对应，容忍旧文件缺字段。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskRow {
        public String id;
        public String capability;
        public int priority;
        public long deadlineEpochMs;
        public int load;
        public long durationMs;
        public boolean failBeforeComplete;
        public long timeoutMs;
        public long createdAtMs;
        public TaskStatus status;
        public String assignedRobotId;
        public long allocatedAtMs;
        public long startedAtMs;
        public long finishedAtMs;
        public int progressPercent;
        public int priorityBoost;
        public String lastDecisionReason;
        public String errorCode;
        public long revision;

        public static TaskRow from(Task t) {
            TaskRow r = new TaskRow();
            r.id = t.getId();
            r.capability = t.getCapability();
            r.priority = t.getPriority();
            r.deadlineEpochMs = t.getDeadlineEpochMs();
            r.load = t.getLoad();
            r.durationMs = t.getDurationMs();
            r.failBeforeComplete = t.isFailBeforeComplete();
            r.timeoutMs = t.getTimeoutMs();
            r.createdAtMs = t.getCreatedAtMs();
            r.status = t.getStatus();
            r.assignedRobotId = t.getAssignedRobotId();
            r.allocatedAtMs = t.getAllocatedAtMs();
            r.startedAtMs = t.getStartedAtMs();
            r.finishedAtMs = t.getFinishedAtMs();
            r.progressPercent = t.getProgressPercent();
            r.priorityBoost = t.getPriorityBoost();
            r.lastDecisionReason = t.getLastDecisionReason();
            r.errorCode = t.getErrorCode();
            r.revision = t.getRevision();
            return r;
        }

        public Task toTask() {
            return new Task(id, capability, priority, deadlineEpochMs, load, durationMs,
                    failBeforeComplete, timeoutMs, createdAtMs, status, assignedRobotId,
                    allocatedAtMs, startedAtMs, finishedAtMs, progressPercent, priorityBoost,
                    lastDecisionReason, errorCode, revision);
        }
    }
}
