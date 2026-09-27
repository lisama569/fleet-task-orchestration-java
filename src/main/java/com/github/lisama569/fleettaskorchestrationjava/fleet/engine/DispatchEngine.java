package com.github.lisama569.fleettaskorchestrationjava.fleet.engine;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;

/**
 * 任务派发执行引擎抽象（模拟，不接真实设备）。
 */
public interface DispatchEngine {
    /** 把已分配任务下发到机器人并开始模拟执行；重复下发同一任务必须幂等。 */
    void dispatch(Task task);

    /** 取消仍在执行的任务；未执行/已结束的调用幂等无副作用。 */
    void cancel(String taskId);

    /** 绑定引擎回调（执行开始/成功/失败/超时）。 */
    void attach(EngineCallbacks callbacks);

    /** 引擎是否仍认为任务在执行中（用于防止重启后重复下发的守卫）。 */
    boolean isRunning(String taskId);
}
