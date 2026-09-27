package com.github.lisama569.fleettaskorchestrationjava.fleet.config;

import com.github.lisama569.fleettaskorchestrationjava.fleet.demo.FleetDemoRunner;
import com.github.lisama569.fleettaskorchestrationjava.fleet.engine.AsyncSimulatedDispatchEngine;
import com.github.lisama569.fleettaskorchestrationjava.fleet.engine.DispatchEngine;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.InMemoryRobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler.CapabilityLoadPolicy;
import com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler.FleetScheduler;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.JsonFileRobotStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.JsonFileTaskStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.RobotStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 车队模块 Spring 装配。 */
@Configuration
@EnableConfigurationProperties(FleetProperties.class)
public class FleetConfig {

    @Bean
    public TaskStore taskStore(FleetProperties props) {
        return new JsonFileTaskStore(props.getTaskStorePath());
    }

    @Bean
    public RobotStore robotStore(FleetProperties props) {
        String taskPath = props.getTaskStorePath();
        String robotPath = taskPath.replaceAll("tasks(?=\\.json$)?", "robots");
        if (robotPath.equals(taskPath)) {
            robotPath = taskPath + ".robots.json";
        }
        return new JsonFileRobotStore(robotPath);
    }

    @Bean
    public RobotRegistry robotRegistry() {
        return new InMemoryRobotRegistry();
    }

    @Bean
    public DispatchEngine dispatchEngine() {
        return new AsyncSimulatedDispatchEngine();
    }

    @Bean
    public CapabilityLoadPolicy allocationPolicy() {
        return new CapabilityLoadPolicy();
    }

    @Bean
    public FleetService fleetService(TaskStore taskStore, RobotStore robotStore, RobotRegistry robotRegistry,
                                     DispatchEngine dispatchEngine, CapabilityLoadPolicy allocationPolicy,
                                     FleetProperties props) {
        FleetService service = new FleetService(taskStore, robotStore, robotRegistry, dispatchEngine,
                allocationPolicy, props);
        // 服务重启：先把未完成任务恢复到可继续调度状态，再启动调度循环（见 FleetScheduler @PostConstruct）
        service.recoverOnStartup();
        return service;
    }

    @Bean
    public FleetScheduler fleetScheduler(FleetService fleetService, FleetProperties props) {
        // start() 由 Spring 生命周期调用；此处构造即可
        return new FleetScheduler(fleetService, props);
    }

    @Bean
    public FleetDemoRunner fleetDemoRunner(FleetService fleetService, FleetProperties props) {
        return new FleetDemoRunner(fleetService, props, props.isDemoEnabled());
    }
}
