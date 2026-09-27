package com.github.lisama569.fleettaskorchestrationjava.store;

import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务内存存储。putIfAbsent 语义保证重复提交不产生重复任务。
 */
@Component
public class TaskStore {
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public Task putIfAbsent(Task task) {
        return tasks.putIfAbsent(task.getTaskId(), task);
    }

    public Task get(String taskId) {
        return tasks.get(taskId);
    }

    public Collection<Task> all() {
        return tasks.values();
    }

    public void clear() {
        tasks.clear();
    }
}
