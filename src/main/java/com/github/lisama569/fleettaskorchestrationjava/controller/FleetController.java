package com.github.lisama569.fleettaskorchestrationjava.controller;

import com.github.lisama569.fleettaskorchestrationjava.model.Assignment;
import com.github.lisama569.fleettaskorchestrationjava.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.service.RobotService;
import com.github.lisama569.fleettaskorchestrationjava.service.Scheduler;
import com.github.lisama569.fleettaskorchestrationjava.service.TaskRejectedException;
import com.github.lisama569.fleettaskorchestrationjava.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地验证用 REST 接口。
 */
@RestController
@RequestMapping("/api")
public class FleetController {

    private final TaskService taskService;
    private final RobotService robotService;
    private final Scheduler scheduler;

    public FleetController(TaskService taskService, RobotService robotService, Scheduler scheduler) {
        this.taskService = taskService;
        this.robotService = robotService;
        this.scheduler = scheduler;
    }

    public record RobotRequest(Set<String> capabilities, int maxLoad) {
    }

    public record TaskRequest(int priority, Instant deadline, String requiredCapability,
                              int requiredLoad, long timeoutMillis) {
    }

    public record ResultRequest(String robotId, long attemptId, boolean success, String detail) {
    }

    public record ProgressRequest(String robotId, long attemptId, int percent) {
    }

    @PostMapping("/robots/{robotId}")
    public Robot registerRobot(@PathVariable String robotId, @RequestBody RobotRequest req) {
        return robotService.register(robotId, req.capabilities(), req.maxLoad());
    }

    @PostMapping("/robots/{robotId}/offline")
    public Robot robotOffline(@PathVariable String robotId) {
        return robotService.markOffline(robotId);
    }

    @PostMapping("/robots/{robotId}/replace")
    public Robot replaceRobot(@PathVariable String robotId, @RequestBody RobotRequest req) {
        return robotService.replace(robotId, req.capabilities(), req.maxLoad());
    }

    @GetMapping("/robots")
    public Collection<Robot> robots() {
        return robotService.all();
    }

    @PostMapping("/tasks/{taskId}")
    public ResponseEntity<?> submitTask(@PathVariable String taskId, @RequestBody TaskRequest req) {
        try {
            Task task = taskService.submit(taskId, req.priority(), req.deadline(),
                    req.requiredCapability(), req.requiredLoad(), req.timeoutMillis());
            return ResponseEntity.ok(task);
        } catch (TaskRejectedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("rejectReason", e.getReason().name(), "message", e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Task cancelTask(@PathVariable String taskId) {
        return taskService.cancel(taskId);
    }

    @PostMapping("/tasks/{taskId}/progress")
    public Task reportProgress(@PathVariable String taskId, @RequestBody ProgressRequest req) {
        return taskService.reportProgress(taskId, req.robotId(), req.attemptId(), req.percent());
    }

    @PostMapping("/tasks/{taskId}/result")
    public Task reportResult(@PathVariable String taskId, @RequestBody ResultRequest req) {
        return taskService.reportResult(taskId, req.robotId(), req.attemptId(), req.success(), req.detail());
    }

    @GetMapping("/tasks")
    public Collection<Task> tasks() {
        return taskService.all();
    }

    @GetMapping("/tasks/{taskId}")
    public Task task(@PathVariable String taskId) {
        return taskService.get(taskId);
    }

    @GetMapping("/assignments")
    public List<Assignment> assignments() {
        return scheduler.assignmentHistory();
    }

    @PostMapping("/schedule")
    public List<Assignment> triggerSchedule() {
        return scheduler.scheduleOnce();
    }
}
