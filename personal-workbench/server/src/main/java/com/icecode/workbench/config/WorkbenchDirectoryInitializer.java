package com.icecode.workbench.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkbenchDirectoryInitializer {

    private final String dataDir;
    private final String homeDir;

    public WorkbenchDirectoryInitializer(@Value("${workbench.data-dir}") String dataDir,
                                         @Value("${workbench.home-dir}") String homeDir) {
        this.dataDir = dataDir;
        this.homeDir = homeDir;
    }

    @PostConstruct
    public void createDirectories() throws IOException {
        Path dataPath = Paths.get(dataDir).toAbsolutePath().normalize();
        Path homePath = Paths.get(homeDir).toAbsolutePath().normalize();
        Files.createDirectories(dataPath);
        Files.createDirectories(dataPath.resolve("backups"));
        Files.createDirectories(dataPath.resolve("attachments"));
        Files.createDirectories(homePath.resolve("logs"));
    }
}
