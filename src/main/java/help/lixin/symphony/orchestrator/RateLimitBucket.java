package help.lixin.symphony.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Rate Limit Bucket (primary/secondary)
 */
public record RateLimitBucket(
        Long remaining,
        Long limit,
        Integer resetInSeconds
) {
    /**
     * 从JSON节点解析RateLimitBucket
     */
    public static RateLimitBucket fromJsonNode(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }

        Long remaining = null;
        Long limit = null;
        Integer resetInSeconds = null;

        if (node.has("remaining")) {
            remaining = parseLong(node.get("remaining"));
        }

        if (node.has("limit")) {
            limit = parseLong(node.get("limit"));
        }

        if (node.has("resetInSeconds")) {
            resetInSeconds = parseInteger(node.get("resetInSeconds"));
        } else if (node.has("resetIn")) {
            // 也支持resetIn字段（秒）
            resetInSeconds = parseInteger(node.get("resetIn"));
        }

        return new RateLimitBucket(remaining, limit, resetInSeconds);
    }

    public static RateLimitBucket fromValues(Object remaining, Object limit, Object resetInSeconds) {
        return new RateLimitBucket(
                parseLong(remaining),
                parseLong(limit),
                parseInteger(resetInSeconds)
        );
    }

    private static Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            if (node.isNumber()) return node.asLong();
            return null;
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Long parseLong(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isNumber()) {
            return null;
        }
        return node.asLong();
    }

    private static Integer parseInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) return ((Long) value).intValue();
        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            if (node.isNumber()) return node.asInt();
            return null;
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer parseInteger(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }
}
