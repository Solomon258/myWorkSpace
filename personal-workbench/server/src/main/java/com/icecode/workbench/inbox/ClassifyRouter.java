package com.icecode.workbench.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 整理路由：配置并启用外部 LLM 时优先调用，任何失败自动回退本地规则。
 * 本地规则永远可用，是无网络、无配置时的唯一路径。
 */
@Service
public class ClassifyRouter implements ClassifyProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassifyRouter.class);

    private final LocalRuleClassifyService localProvider;
    private final LlmClassifyProvider llmProvider;

    public ClassifyRouter(LocalRuleClassifyService localProvider, LlmClassifyProvider llmProvider) {
        this.localProvider = localProvider;
        this.llmProvider = llmProvider;
    }

    @Override
    public String name() { return llmProvider.isEnabled() ? "llm+local" : "local"; }

    @Override
    public ClassifySuggestionVO classify(String raw, String timezone) {
        if (llmProvider.isEnabled()) {
            try {
                // LLM 的结果也要过一遍「每周 X」识别。两个理由：
                //   ① 那是确定性的文本特征，不该赌模型这一轮发挥得如何；
                //   ② 没配 AI 时走的是本地规则 —— 两条路径对同一句话必须给出同样结论，
                //      否则「配了 AI 之后重复识别凭空消失」这种差异在界面上完全看不出来。
                return localProvider.applyRepeatHint(raw, llmProvider.classify(raw, timezone));
            } catch (Exception exception) {
                // 不输出密钥与原始报文，只记录异常类型与消息
                LOGGER.warn("LLM classify failed, fallback to local rules: {} - {}",
                        exception.getClass().getSimpleName(), exception.getMessage());
            }
        }
        return localProvider.classify(raw, timezone);
    }

    public long[] drainTokens() { return llmProvider.drainTokens(); }
}
