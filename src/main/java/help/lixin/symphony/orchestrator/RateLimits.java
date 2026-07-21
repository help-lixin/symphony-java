package help.lixin.symphony.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Rate Limits 信息
 */
public record RateLimits(
        String limitId,
        RateLimitBucket primary,
        RateLimitBucket secondary,
        RateLimitCredits credits
) {
    /**
     * 从JSON节点解析RateLimits
     */
    public static RateLimits fromJsonNode(JsonNode node, String defaultLimitId) {
        if (node == null || node.isMissingNode()) {
            return null;
        }

        String limitId = defaultLimitId;
        if (node.has("limitId")) {
            limitId = node.get("limitId").asText(limitId);
        }

        RateLimitBucket primary = null;
        RateLimitBucket secondary = null;
        RateLimitCredits credits = null;

        if (node.has("primary")) {
            primary = RateLimitBucket.fromJsonNode(node.get("primary"));
        }

        if (node.has("secondary")) {
            secondary = RateLimitBucket.fromJsonNode(node.get("secondary"));
        }

        if (node.has("credits")) {
            credits = RateLimitCredits.fromJsonNode(node.get("credits"));
        }

        return new RateLimits(limitId, primary, secondary, credits);
    }
}
