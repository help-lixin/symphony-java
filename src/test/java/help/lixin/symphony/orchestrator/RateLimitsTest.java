package help.lixin.symphony.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimits 解析测试
 * 
 * 测试 RateLimits、RateLimitBucket、RateLimitCredits 的 JSON 解析功能
 */
class RateLimitsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testRateLimitBucketFromJsonNode() throws Exception {
        String json = "{\"remaining\": 100, \"limit\": 200, \"resetInSeconds\": 60}";
        
        JsonNode node = objectMapper.readTree(json);
        RateLimitBucket bucket = RateLimitBucket.fromJsonNode(node);
        
        assertNotNull(bucket);
        assertEquals(100L, bucket.remaining());
        assertEquals(200L, bucket.limit());
        assertEquals(60, bucket.resetInSeconds());
    }

    @Test
    void testRateLimitBucketWithMissingFields() throws Exception {
        String json = "{\"remaining\": 50}";
        
        JsonNode node = objectMapper.readTree(json);
        RateLimitBucket bucket = RateLimitBucket.fromJsonNode(node);
        
        assertNotNull(bucket);
        assertEquals(50L, bucket.remaining());
        assertNull(bucket.limit());
        assertNull(bucket.resetInSeconds());
    }

    @Test
    void testRateLimitBucketNullNode() {
        RateLimitBucket bucket = RateLimitBucket.fromJsonNode(null);
        assertNull(bucket);
    }

    @Test
    void testRateLimitBucketMissingNode() throws Exception {
        String json = "{}";
        JsonNode node = objectMapper.readTree(json);
        RateLimitBucket bucket = RateLimitBucket.fromJsonNode(node.get("missing"));
        assertNull(bucket);
    }

    @Test
    void testRateLimitCreditsFromJsonNode() throws Exception {
        String json = "{\"unlimited\": false, \"hasCredits\": true, \"balance\": 1500.0}";
        
        JsonNode node = objectMapper.readTree(json);
        RateLimitCredits credits = RateLimitCredits.fromJsonNode(node);
        
        assertNotNull(credits);
        assertEquals(Boolean.FALSE, credits.unlimited());
        assertEquals(Boolean.TRUE, credits.hasCredits());
        assertEquals(1500.0, credits.balance());
    }

    @Test
    void testRateLimitCreditsUnlimited() throws Exception {
        String json = "{\"unlimited\": true}";
        
        JsonNode node = objectMapper.readTree(json);
        RateLimitCredits credits = RateLimitCredits.fromJsonNode(node);
        
        assertNotNull(credits);
        assertEquals(Boolean.TRUE, credits.unlimited());
        assertNull(credits.balance());
    }

    @Test
    void testRateLimitCreditsNullNode() {
        RateLimitCredits credits = RateLimitCredits.fromJsonNode(null);
        assertNull(credits);
    }

    @Test
    void testRateLimitsFromJsonNode() throws Exception {
        String json = "{\"limitId\": \"model-limit\", \"primary\": {\"remaining\": 100, \"limit\": 200, \"resetInSeconds\": 60}, \"secondary\": {\"remaining\": 50, \"limit\": 100}, \"credits\": {\"hasCredits\": true, \"balance\": 1500.0}}";
        
        JsonNode node = objectMapper.readTree(json);
        RateLimits limits = RateLimits.fromJsonNode(node, "default-limit");
        
        assertNotNull(limits);
        assertEquals("model-limit", limits.limitId());
        
        assertNotNull(limits.primary());
        assertEquals(100L, limits.primary().remaining());
        assertEquals(200L, limits.primary().limit());
        
        assertNotNull(limits.secondary());
        assertEquals(50L, limits.secondary().remaining());
        
        assertNotNull(limits.credits());
        assertEquals(Boolean.TRUE, limits.credits().hasCredits());
        assertEquals(1500.0, limits.credits().balance());
    }

    @Test
    void testRateLimitsWithDefaultLimitId() throws Exception {
        String json = "{\"primary\": {\"remaining\": 100, \"limit\": 200}}";
        
        JsonNode node = objectMapper.readTree(json);
        RateLimits limits = RateLimits.fromJsonNode(node, "custom-default");
        
        assertNotNull(limits);
        assertEquals("custom-default", limits.limitId());
    }

    @Test
    void testRateLimitsNullNode() {
        RateLimits limits = RateLimits.fromJsonNode(null, "test");
        assertNull(limits);
    }
}
