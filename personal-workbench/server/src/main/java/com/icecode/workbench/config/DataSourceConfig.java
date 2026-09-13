package com.icecode.workbench.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.sql.DataSource;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${workbench.data-dir}") String dataDir) throws IOException {
        Path dataPath = Paths.get(dataDir).toAbsolutePath().normalize();
        Files.createDirectories(dataPath);
        Path databasePath = dataPath.resolve("workbench.db");
        String normalizedPath = databasePath.toString().replace('\\', '/');

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(config);
        sqliteDataSource.setUrl("jdbc:sqlite:" + normalizedPath);

        // Windows 下每次新开 SQLite 连接都要重建 db/wal/shm 三个文件句柄，
        // 会被实时防护软件逐个扫描，实测单次连接固定开销约 160ms，
        // 驾驶舱这类十几次查询的接口因此被放大到 2s。池化复用后回到毫秒级。
        // 池大小 4：SQLite WAL 单写多读，并发写入由 busy_timeout=5000 排队兜底。
        // 恢复备份需要替换 db 文件：BackupService 会先驱逐池内连接释放句柄。
        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqliteDataSource);
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTestQuery("SELECT 1");
        hikari.setPoolName("workbench-sqlite");
        // 恢复备份替换 db 文件期间需要挂起池，禁止出借/新建连接
        hikari.setAllowPoolSuspension(true);
        return new HikariDataSource(hikari);
    }
}
