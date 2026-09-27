package com.github.lisama569.fleettaskorchestrationjava.fleet.engine;

/** 引擎 -> 服务的事件回调。所有回调必须被幂等处理（重复触发不能产生重复副作用）。 */
public interface EngineCallbacks {
    void onStarted(String taskId);
    void onProgress(String taskId, int percent);
    void onSucceeded(String taskId);
    void onFailed(String taskId, String errorCode);
    void onTimedOut(String taskId, String errorCode);
}
