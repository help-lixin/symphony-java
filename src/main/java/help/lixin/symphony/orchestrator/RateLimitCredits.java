package help.lixin.symphony.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Rate Limit Credits
 */
public record RateLimitCredits(
        Boolean unlimited,
        Boolean hasCredits,
        Double balance
) {
    /**
     * 从JSON节点解析RateLimitCredits
     */
    public static RateLimitCredits fromJsonNode(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }

        Boolean unlimited = null;
        Boolean hasCredits = null;
        Double balance = null;

        if (node.has("unlimited")) {
            unlimited = parseBoolean(node.get("unlimited"));
        }

        if (node.has("hasCredits")) {
            hasCredits = parseBoolean(node.get("hasCredits"));
        } else if (node.has("has_credits")) {
            hasCredits = parseBoolean(node.get("has_credits"));
        }

        if (node.has("balance")) {
            balance = parseDouble(node.get("balance"));
        }

        return new RateLimitCredits(unlimited, hasCredits, balance);
    }

    public static RateLimitCredits fromValues(Object unlimited, Object hasCredits, Object balance) {
        return new RateLimitCredits(
                parseBoolean(unlimited),
                parseBoolean(hasCredits),
                parseDouble(balance)
        );
    }

    private static Boolean parseBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            if (node.isBoolean()) return node.asBoolean();
            if (node.isTextual()) return Boolean.parseBoolean(node.asText());
            return null;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }

    private static Boolean parseBoolean(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) return node.asBoolean();
        if (node.isTextual()) return Boolean.parseBoolean(node.asText());
        return null;
    }

    private static Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Float) return ((Float) value).doubleValue();
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            if (node.isNumber()) return node.asDouble();
            return null;
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double parseDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isNumber()) {
            return null;
        }
        return node.asDouble();
    }
}
