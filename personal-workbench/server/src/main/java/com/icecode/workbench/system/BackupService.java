package com.icecode.workbench.system;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.sqlite.SQLiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;

@Service
public class BackupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupService.class);
    private static final int KEEP_BACKUPS = 14;
    private static final int KEEP_PRE_RESTORE = 5;
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.db$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String[] VERIFY_TABLES = {"task", "favorite", "inbox_item", "schedule_event", "pomodoro"};

    private final JdbcTemplate jdbcTemplate;
    private final Path dataDir;

    public BackupService(JdbcTemplate jdbcTemplate, @Value("${workbench.data-dir}") String dataDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataDir = Paths.get(dataDir).toAbsolutePath().normalize();
    }

    public synchronized BackupResultVO backupNow(String targetPath) {
        try {
            Files.createDirectories(backupsDir());
            Path target = resolveTarget(targetPath, "workbench-");
            vacuumInto(target);
            prune("workbench-", KEEP_BACKUPS);
            return new BackupResultVO(target.getFileName().toString(), Files.size(target));
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.error("Backup failed", exception);
            throw new BizException(ErrorCode.BACKUP_FAILED);
        }
    }

    public List<BackupFileVO> listBackups() {
        List<BackupFileVO> result = new ArrayList<BackupFileVO>();
        Path dir = backupsDir();
        if (!Files.isDirectory(dir)) return result;
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.db")) {
            for (Path file : stream) files.add(file);
        } catch (IOException exception) {
            return result;
        }
        files.sort(new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException exception) {
                    return 0;
                }
            }
        });
        for (Path file : files) {
            try {
                FileTime modified = Files.getLastModifiedTime(file);
                result.add(new BackupFileVO(file.getFileName().toString(), Files.size(file),
                        modified.toInstant().toString()));
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    public synchronized RestoreResultVO restore(String fileName) {
        if (!SAFE_NAME.matcher(fileName).matches()) throw new BizException(ErrorCode.INVALID_PARAMETER, "备份文件名不合法（必须以 .db 结尾，且只允许字母、数字、点、下划线、中划线）");
        Path backup = backupsDir().resolve(fileName).normalize();
        if (!backup.startsWith(backupsDir()) || !Files.isRegularFile(backup)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        int migrations = validateBackup(backup, counts);
        try {
            BackupResultVO preRestore = doBackup("pre-restore-");
            prune("pre-restore-", KEEP_PRE_RESTORE);
            jdbcTemplate.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            // Hikari 池化后连接持有 db/wal/shm 文件句柄，Windows 不允许替换被占用文件。
            // 且驱逐后池会因 minimumIdle 立即补建连接，恰逢文件替换窗口（路径短暂缺失）
            // 时 SQLite 会按空库打开，导致后续查询 "no such table"。
            // 因此：先挂起池（不再出借/新建连接）→ 驱逐 → 替换文件 → 恢复池。
            suspendAndEvictPool();
            try {
                Path dbFile = dataDir.resolve("workbench.db");
                copyWithRetry(backup, dbFile);
                deleteWithRetry(dataDir.resolve("workbench.db-wal"));
                deleteWithRetry(dataDir.resolve("workbench.db-shm"));
            } finally {
                resumePool();
            }
            LOGGER.info("Restored database from {}", fileName);
            return new RestoreResultVO(fileName, preRestore.getFile(), migrations, counts);
        } catch (Exception exception) {
            LOGGER.error("Restore failed", exception);
            throw new BizException(ErrorCode.BACKUP_FAILED);
        }
    }

    private void suspendAndEvictPool() {
        javax.sql.DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource instanceof HikariDataSource) {
            HikariPoolMXBean poolMXBean = ((HikariDataSource) dataSource).getHikariPoolMXBean();
            if (poolMXBean != null) {
                poolMXBean.suspendPool();
                poolMXBean.softEvictConnections();
            }
        }
    }

    private void resumePool() {
        javax.sql.DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource instanceof HikariDataSource) {
            HikariPoolMXBean poolMXBean = ((HikariDataSource) dataSource).getHikariPoolMXBean();
            if (poolMXBean != null) poolMXBean.resumePool();
        }
    }

    private void copyWithRetry(Path source, Path target) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException exception) {
                lastError = exception;
                Thread.sleep(300);
            }
        }
        throw lastError;
    }

    private void deleteWithRetry(Path path) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException exception) {
                lastError = exception;
                Thread.sleep(300);
            }
        }
        if (lastError != null) throw lastError;
    }

    private BackupResultVO doBackup(String prefix) throws IOException {
        Files.createDirectories(backupsDir());
        Path target = backupsDir().resolve(prefix + LocalDateTime.now().format(STAMP) + ".db");
        vacuumInto(target);
        return new BackupResultVO(target.getFileName().toString(), Files.size(target));
    }

    private void vacuumInto(Path target) throws IOException {
        Files.deleteIfExists(target);
        String sql = "VACUUM INTO '" + target.toString().replace('\\', '/').replace("'", "''") + "'";
        jdbcTemplate.execute(sql);
    }

    private Path resolveTarget(String targetPath, String prefix) {
        if (targetPath == null || targetPath.trim().isEmpty()) {
            return backupsDir().resolve(prefix + LocalDateTime.now().format(STAMP) + ".db");
        }
        Path candidate = Paths.get(targetPath.trim());
        if (!candidate.isAbsolute()) candidate = backupsDir().resolve(candidate);
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(backupsDir())
                || !SAFE_NAME.matcher(candidate.getFileName().toString()).matches()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "备份路径必须落在备份目录内，且文件名以 .db 结尾");
        }
        return candidate;
    }

    private int validateBackup(Path backup, Map<String, Integer> counts) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        String url = "jdbc:sqlite:" + backup.toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url, config.toProperties());
             Statement statement = connection.createStatement()) {
            int migrations;
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND script LIKE 'V%'")) {
                migrations = rs.next() ? rs.getInt(1) : 0;
            }
            if (migrations < 3) throw new BizException(ErrorCode.RESTORE_INVALID);
            for (String table : VERIFY_TABLES) {
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    counts.put(table, rs.next() ? rs.getInt(1) : 0);
                }
            }
            return migrations;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.warn("Backup validation failed for {}: {}", backup, exception.getMessage());
            throw new BizException(ErrorCode.RESTORE_INVALID);
        }
    }

    private void prune(String prefix, int keep) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupsDir(), prefix + "*.db")) {
            for (Path file : stream) files.add(file);
        }
        files.sort(new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException exception) {
                    return 0;
                }
            }
        });
        for (int i = keep; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    private Path backupsDir() { return dataDir.resolve("backups"); }
}
