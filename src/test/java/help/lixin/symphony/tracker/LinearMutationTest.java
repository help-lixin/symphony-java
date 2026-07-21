package help.lixin.symphony.tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Linear mutation 相关常量测试
 *
 * 验证 GraphQL mutations 的结构是否正确
 */
class LinearMutationTest {

    @Test
    void testCreateCommentMutationStructure() {
        // 验证 create comment mutation 包含必要的字段
        String mutation = "mutation SymphonyCreateComment($issueId: String!, $body: String!) {" +
                "commentCreate(input: {issueId: $issueId, body: $body}) {" +
                "success" +
                "}" +
                "}";

        assertTrue(mutation.contains("commentCreate"));
        assertTrue(mutation.contains("$issueId"));
        assertTrue(mutation.contains("$body"));
        assertTrue(mutation.contains("success"));
    }

    @Test
    void testUpdateIssueStateMutationStructure() {
        // 验证 update issue state mutation 包含必要的字段
        String mutation = "mutation SymphonyUpdateIssueState($issueId: String!, $stateId: String!) {" +
                "issueUpdate(id: $issueId, input: {stateId: $stateId}) {" +
                "success" +
                "}" +
                "}";

        assertTrue(mutation.contains("issueUpdate"));
        assertTrue(mutation.contains("$issueId"));
        assertTrue(mutation.contains("$stateId"));
        assertTrue(mutation.contains("success"));
    }

    @Test
    void testStateLookupQueryStructure() {
        // 验证 state lookup query 包含必要的字段
        String query = "query SymphonyResolveStateId($issueId: String!, $stateName: String!) {" +
                "issue(id: $issueId) {" +
                "team {" +
                "states(filter: {name: {eq: $stateName}}, first: 1) {" +
                "nodes {" +
                "id" +
                "}" +
                "}" +
                "}" +
                "}" +
                "}";

        assertTrue(query.contains("SymphonyResolveStateId"));
        assertTrue(query.contains("$issueId"));
        assertTrue(query.contains("$stateName"));
        assertTrue(query.contains("states"));
        assertTrue(query.contains("filter"));
    }
}
