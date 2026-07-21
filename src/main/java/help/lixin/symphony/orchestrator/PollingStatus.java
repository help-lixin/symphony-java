package help.lixin.symphony.orchestrator;

/**
 * 轮询状态
 */
public record PollingStatus(boolean checking, Long nextPollInMs, int pollIntervalMs) {}