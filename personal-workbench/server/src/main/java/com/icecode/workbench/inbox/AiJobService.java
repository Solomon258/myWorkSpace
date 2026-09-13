package com.icecode.workbench.inbox;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

@Service
public class AiJobService {

    private final AiJobRepository aiJobRepository;
    private final InboxService inboxService;
    private final AppConfigRepository configRepository;
    private final ClassifyRouter classifyRouter;

    public AiJobService(AiJobRepository aiJobRepository, InboxService inboxService,
                        AppConfigRepository configRepository, ClassifyRouter classifyRouter) {
        this.aiJobRepository = aiJobRepository;
        this.inboxService = inboxService;
        this.configRepository = configRepository;
        this.classifyRouter = classifyRouter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public long startClassifyJob() {
        long startedAt = System.currentTimeMillis();
        String timezone = timezone();
        String now = TimeUtil.now(timezone);
        long jobId = aiJobRepository.insertPending("classify", null, now);
        try {
            aiJobRepository.markRunning(jobId, now);
            inboxService.classifyPending();
            long[] tokens = classifyRouter.drainTokens();
            if (tokens[0] > 0 || tokens[1] > 0) {
                aiJobRepository.markSuccess(jobId, System.currentTimeMillis() - startedAt, tokens[0], tokens[1], TimeUtil.now(timezone));
            } else {
                aiJobRepository.markSuccess(jobId, System.currentTimeMillis() - startedAt, TimeUtil.now(timezone));
            }
            return jobId;
        } catch (Exception exception) {
            aiJobRepository.markFailed(jobId, "整理失败，请稍后重试", System.currentTimeMillis() - startedAt, TimeUtil.now(timezone));
            throw exception;
        }
    }

    public Map<String, Object> status(long jobId) {
        AiJobVO job = aiJobRepository.findById(jobId);
        if (job == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("job", job);
        result.put("processed", inboxService.list("processed"));
        return result;
    }

    public void failStaleJobs() {
        aiJobRepository.failStaleJobs(TimeUtil.now(timezone()));
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }
}
