package com.github.lisama569.fleettaskorchestrationjava.fleet.web;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.CancelResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.ReportResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.SubmitResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 本地验证用 REST 接口。 */
@RestController
@RequestMapping("/fleet")
public class FleetController {

    private final FleetService fleetService;

    public FleetController(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    public record RobotDto(String robotId, Set<String> capabilities, int capacity) { }
    public record TaskDto(String taskId, String capability, int priority, long deadlineEpochMs, int load,
                          long durationMs, boolean failBeforeComplete, long timeoutMs) { }
    public record ReportDto(String robotId, Integer percent, String errorCode) { }
    public record ReplaceDto(String oldRobotId, String newRobotId, Set<String> capabilities, int capacity) { }

    @PostMapping("/robots")
    public Robot register(@RequestBody RobotDto dto) {
        return fleetService.registerRobot(dto.robotId(), dto.capabilities(), dto.capacity());
    }

    @GetMapping("/robots")
    public List<Robot> robots() {
        return fleetService.listRobots();
    }

    @PostMapping("/tasks")
    public SubmitResult submit(@RequestBody TaskDto dto) {
        TaskRequest req = new TaskRequest(dto.taskId(), dto.capability(), dto.priority(),
                dto.deadlineEpochMs(), dto.load(), dto.durationMs(),
                dto.failBeforeComplete(), dto.timeoutMs());
        return fleetService.submit(req);
    }

    @GetMapping("/tasks")
    public List<Task> tasks() {
        return fleetService.listTasks();
    }

    @GetMapping("/tasks/{taskId}")
    public Task task(@PathVariable String taskId) {
        return fleetService.getTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/progress")
    public ReportResult progress(@PathVariable String taskId, @RequestBody ReportDto dto) {
        int percent = dto.percent() == null ? 0 : dto.percent();
        return fleetService.reportProgress(taskId, dto.robotId(), percent);
    }

    @PostMapping("/tasks/{taskId}/success")
    public ReportResult success(@PathVariable String taskId, @RequestBody ReportDto dto) {
        return fleetService.reportSuccess(taskId, dto.robotId());
    }

    @PostMapping("/tasks/{taskId}/failure")
    public ReportResult failure(@PathVariable String taskId, @RequestBody ReportDto dto) {
        return fleetService.reportFailure(taskId, dto.robotId(),
                dto.errorCode() == null ? "MANUAL_FAILURE" : dto.errorCode());
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public CancelResult cancel(@PathVariable String taskId) {
        return fleetService.cancel(taskId);
    }

    @PostMapping("/robots/{robotId}/offline")
    public Map<String, String> offline(@PathVariable String robotId) {
        fleetService.robotOffline(robotId);
        return Map.of("robotId", robotId, "status", "OFFLINE");
    }

    @PostMapping("/robots/{robotId}/online")
    public Map<String, String> online(@PathVariable String robotId) {
        fleetService.robotOnline(robotId);
        return Map.of("robotId", robotId, "status", "ONLINE");
    }

    @PostMapping("/robots/replace")
    public Robot replace(@RequestBody ReplaceDto dto) {
        return fleetService.replaceRobot(dto.oldRobotId(), dto.newRobotId(),
                dto.capabilities(), dto.capacity());
    }
}
