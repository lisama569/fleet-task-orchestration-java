package com.github.lisama569.fleettaskorchestrationjava.fleet.store;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import java.util.Collection;
import java.util.List;

/** 任务本地存储抽象（内存实现/JSON 文件实现）。 */
public interface TaskStore {
    void saveAll(Collection<Task> tasks);
    List<Task> loadAll();
}
