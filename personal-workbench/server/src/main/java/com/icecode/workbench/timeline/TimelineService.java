package com.icecode.workbench.timeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;

@Service
public class TimelineService {

    private static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TimelineItemVO> rowMapper = new RowMapper<TimelineItemVO>() {
        @Override
        public TimelineItemVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            String createdAt = rs.getString("created_at");
            String day = createdAt == null || createdAt.length() < 10 ? createdAt : createdAt.substring(0, 10);
            return new TimelineItemVO(rs.getLong("id"), rs.getString("log_type"),
                    rs.getString("category"), rs.getString("content"), createdAt, day);
        }
    };

    public TimelineService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TimelineItemVO> list(int limit) {
        int safeLimit = Math.min(MAX_LIMIT, Math.max(1, limit));
        return jdbcTemplate.query("SELECT id, log_type, category, content, created_at FROM activity_log ORDER BY created_at DESC, id DESC LIMIT ?",
                rowMapper, safeLimit);
    }

    public void delete(long id) {
        int affected = jdbcTemplate.update("DELETE FROM activity_log WHERE id = ?", id);
        if (affected == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "这条时间线记录不存在或已经被删除");
        }
    }
}
