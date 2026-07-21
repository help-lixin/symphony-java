package help.lixin.symphony.orchestrator;

/**
 * Codex统计数据
 */
public record CodexTotals(long inputTokens, long outputTokens, long totalTokens, long secondsRunning) {}