package com.github.lisama569.fleettaskorchestrationjava.fleet.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RobotStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 机器人名录的本地 JSON 存储，原子改名写入。 */
public class JsonFileRobotStore implements RobotStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileRobotStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileRobotStore(String path) {
        this.file = Path.of(path);
    }

    @Override
    public synchronized void saveAll(List<Robot> robots) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            List<Row> rows = robots.stream().map(Row::from).toList();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), rows);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("持久化机器人失败: " + file, e);
        }
    }

    @Override
    public synchronized List<Robot> loadAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<Row> rows = mapper.readerForListOf(Row.class).readValue(file.toFile());
            List<Robot> robots = rows.stream().map(Row::toRobot).toList();
            log.info("[store] 从 {} 加载机器人 {} 台", file, robots.size());
            return robots;
        } catch (IOException e) {
            throw new UncheckedIOException("读取机器人存储失败: " + file, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Row {
        public String id;
        public Set<String> capabilities;
        public int capacity;
        public RobotStatus status;

        public static Row from(Robot r) {
            Row row = new Row();
            row.id = r.getId();
            row.capabilities = new HashSet<>(r.getCapabilities());
            row.capacity = r.getCapacity();
            row.status = r.getStatus();
            return row;
        }

        public Robot toRobot() {
            Robot r = new Robot(id, capabilities == null ? Set.of() : capabilities, capacity);
            // 重启后掉线机器人保持 OFFLINE（需要显式上线）；BUSY 归属已失效，在 FleetService 中复位
            if (status == RobotStatus.OFFLINE) {
                r.markOffline();
            }
            return r;
        }
    }
}
