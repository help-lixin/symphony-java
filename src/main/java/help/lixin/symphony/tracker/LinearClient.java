package help.lixin.symphony.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.model.Issue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Linear GraphQL API客户端
 *
 * 功能说明:
 * - 封装与Linear GraphQL API的通信
 * - 处理认证、分页和错误处理
 * - 将API响应规范化为Issue对象
 *
 * GraphQL查询:
 * - SymphonyLinearPoll: 按状态查询问题
 * - SymphonyLinearIssuesById: 批量获取问题
 */
public class LinearClient {
    private static final Logger logger = LoggerFactory.getLogger(LinearClient.class);
    private static final String LINEAR_API_ENDPOINT = "https://api.linear.app/graphql";
    private static final int PAGE_SIZE = 50;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String endpoint;

    // GraphQL查询语句
    private static final String POLL_QUERY = """
            query SymphonyLinearPoll($projectSlug: String!, $stateNames: [String!]!, $first: Int!, $relationFirst: Int!, $after: String) {
              issues(filter: {project: {slugId: {eq: $projectSlug}}, state: {name: {in: $stateNames}}}, first: $first, after: $after) {
                nodes {
                  id
                  identifier
                  title
                  description
                  priority
                  state { name }
                  branchName
                  url
                  assignee { id }
                  labels { nodes { name } }
                  inverseRelations(first: $relationFirst) {
                    nodes {
                      type
                      issue {
                        id
                        identifier
                        state { name }
                      }
                    }
                  }
                  createdAt
                  updatedAt
                }
                pageInfo {
                  hasNextPage
                  endCursor
                }
              }
            }
            """;

    private static final String ISSUES_BY_ID_QUERY = """
            query SymphonyLinearIssuesById($ids: [ID!]!, $first: Int!, $relationFirst: Int!) {
              issues(filter: {id: {in: $ids}}, first: $first) {
                nodes {
                  id
                  identifier
                  title
                  description
                  priority
                  state { name }
                  branchName
                  url
                  assignee { id }
                  labels { nodes { name } }
                  inverseRelations(first: $relationFirst) {
                    nodes {
                      type
                      issue {
                        id
                        identifier
                        state { name }
                      }
                    }
                  }
                  createdAt
                  updatedAt
                }
              }
            }
            """;

    // Mutations
    private static final String CREATE_COMMENT_MUTATION = """
            mutation SymphonyCreateComment($issueId: String!, $body: String!) {
              commentCreate(input: {issueId: $issueId, body: $body}) {
                success
              }
            }
            """;

    private static final String UPDATE_ISSUE_STATE_MUTATION = """
            mutation SymphonyUpdateIssueState($issueId: String!, $stateId: String!) {
              issueUpdate(id: $issueId, input: {stateId: $stateId}) {
                success
              }
            }
            """;

    private static final String STATE_LOOKUP_QUERY = """
            query SymphonyResolveStateId($issueId: String!, $stateName: String!) {
              issue(id: $issueId) {
                team {
                  states(filter: {name: {eq: $stateName}}, first: 1) {
                    nodes {
                      id
                    }
                  }
                }
              }
            }
            """;

    // 获取 Issue 详情的查询
    private static final String GET_ISSUE_QUERY = """
            query SymphonyGetIssue($issueId: String!) {
              issue(id: $issueId) {
                id
                identifier
                title
                description
                priority
                state { name }
                branchName
                url
                assignee { id name }
                labels { nodes { name } }
                inverseRelations(first: 50) {
                  nodes {
                    type
                    issue {
                      id
                      identifier
                      state { name }
                    }
                  }
                }
                createdAt
                updatedAt
              }
            }
            """;

    // 获取 Issue 详情的查询（通过 identifier）
    private static final String GET_ISSUE_BY_IDENTIFIER_QUERY = """
            query SymphonyGetIssueByIdentifier($identifier: String!) {
              issue(identifier: $identifier) {
                id
                identifier
                title
                description
                priority
                state { name }
                branchName
                url
                assignee { id name }
                labels { nodes { name } }
                inverseRelations(first: 50) {
                  nodes {
                    type
                    issue {
                      id
                      identifier
                      state { name }
                    }
                  }
                }
                createdAt
                updatedAt
              }
            }
            """;

    // 获取评论列表的查询
    private static final String GET_COMMENTS_QUERY = """
            query SymphonyGetComments($issueId: String!) {
              issue(id: $issueId) {
                comments(first: 50) {
                  nodes {
                    id
                    body
                    createdAt
                    updatedAt
                    user { id name }
                  }
                }
              }
            }
            """;

    public LinearClient(AppConfig.TrackerConfig config) {
        this.apiKey = config.getApiKey();
        this.endpoint = config.getEndpoint() != null ? config.getEndpoint() : LINEAR_API_ENDPOINT;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    /**
     * 获取指定状态的问题
     *
     * @param projectSlug 项目slug
     * @param stateNames 状态名称列表
     * @return 问题列表
     */
    public CompletionStage<List<Issue>> fetchIssues(String projectSlug, List<String> stateNames) {
        logger.info("fetchIssues called: projectSlug={}, stateNames={}", projectSlug, stateNames);
        return fetchIssuesRecursive(projectSlug, stateNames, null, new ArrayList<>());
    }

    private CompletionStage<List<Issue>> fetchIssuesRecursive(
            String projectSlug, List<String> stateNames, String afterCursor, List<Issue> acc) {
        logger.info("fetchIssuesRecursive: projectSlug={}, stateNames={}, afterCursor={}", projectSlug, stateNames, afterCursor);

        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("projectSlug", projectSlug);
        variables.putPOJO("stateNames", stateNames);
        variables.put("first", PAGE_SIZE);
        variables.put("relationFirst", PAGE_SIZE);
        if (afterCursor != null) {
            variables.put("after", afterCursor);
        }

        return executeQuery(POLL_QUERY, variables)
                .thenApply(response -> {
                    JsonNode issuesNode = response.path("data").path("issues");
                    JsonNode nodes = issuesNode.path("nodes");

                    List<Issue> newIssues = new ArrayList<>();
                    for (JsonNode node : nodes) {
                        newIssues.add(normalizeIssue(node));
                    }

                    List<Issue> combined = new ArrayList<>(acc);
                    combined.addAll(newIssues);

                    boolean hasNextPage = issuesNode.path("pageInfo").path("hasNextPage").asBoolean(false);
                    String endCursor = issuesNode.path("pageInfo").path("endCursor").asText(null);

                    if (hasNextPage && endCursor != null) {
                        // 需要继续获取下一页（同步方式）
                        return fetchNextPagesSync(projectSlug, stateNames, endCursor, combined);
                    } else {
                        return combined;
                    }
                });
    }

    private List<Issue> fetchNextPagesSync(String projectSlug, List<String> stateNames,
                                          String afterCursor, List<Issue> acc) {
        List<Issue> result = new ArrayList<>(acc);

        while (true) {
            try {
                ObjectNode variables = objectMapper.createObjectNode();
                variables.put("projectSlug", projectSlug);
                variables.putPOJO("stateNames", stateNames);
                variables.put("first", PAGE_SIZE);
                variables.put("relationFirst", PAGE_SIZE);
                variables.put("after", afterCursor);

                HttpRequest request = buildRequest(POLL_QUERY, variables);
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                JsonNode issuesNode = objectMapper.readTree(response.body())
                        .path("data").path("issues");
                JsonNode nodes = issuesNode.path("nodes");

                for (JsonNode node : nodes) {
                    result.add(normalizeIssue(node));
                }

                boolean hasNextPage = issuesNode.path("pageInfo").path("hasNextPage").asBoolean(false);
                String endCursor = issuesNode.path("pageInfo").path("endCursor").asText(null);

                if (!hasNextPage || endCursor == null) {
                    break;
                }

                afterCursor = endCursor;

            } catch (Exception e) {
                logger.error("获取下一页失败", e);
                break;
            }
        }

        return result;
    }

    /**
     * 根据ID列表获取问题
     *
     * @param issueIds 问题ID列表
     * @return 问题列表
     */
    public CompletionStage<List<Issue>> fetchIssuesByIds(List<String> issueIds) {
        if (issueIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // 批量处理，每批50个
        List<List<String>> batches = partition(issueIds, PAGE_SIZE);
        List<Issue> allIssues = new ArrayList<>();

        // 同步获取所有批次
        for (List<String> batch : batches) {
            try {
                List<Issue> batchIssues = fetchBatchByIds(batch).toCompletableFuture().join();
                allIssues.addAll(batchIssues);
            } catch (Exception e) {
                logger.error("批量获取问题失败: {}", batch, e);
            }
        }

        // 按原始顺序排序
        return CompletableFuture.completedFuture(sortByIdOrder(allIssues, issueIds));
    }

    private CompletionStage<List<Issue>> fetchBatchByIds(List<String> issueIds) {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.putPOJO("ids", issueIds);
        variables.put("first", issueIds.size());
        variables.put("relationFirst", PAGE_SIZE);

        return executeQuery(ISSUES_BY_ID_QUERY, variables)
                .thenApply(response -> {
                    JsonNode nodes = response.path("data").path("issues").path("nodes");
                    List<Issue> issues = new ArrayList<>();
                    for (JsonNode node : nodes) {
                        issues.add(normalizeIssue(node));
                    }
                    return issues;
                });
    }

    /**
     * 执行GraphQL查询
     *
     * @param query GraphQL查询语句
     * @param variables 查询变量
     * @return 响应JSON
     */
    private CompletionStage<JsonNode> executeQuery(String query, ObjectNode variables) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("query", query);
                requestBody.set("variables", variables);

                logger.info("Executing GraphQL request to: {}", endpoint);

                HttpRequest request = buildRequest(query, variables);
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                logger.info("GraphQL response status: {}", response.statusCode());

                if (response.statusCode() != 200) {
                    logger.error("Linear API请求失败: status={}, body={}",
                            response.statusCode(), response.body());
                    throw new RuntimeException("Linear API请求失败: " + response.statusCode());
                }

                JsonNode responseJson = objectMapper.readTree(response.body());

                // 检查GraphQL错误
                JsonNode errors = responseJson.path("errors");
                if (errors.isArray() && errors.size() > 0) {
                    String errorMessage = errors.get(0).path("message").asText("Unknown error");
                    logger.error("GraphQL错误: {}", errorMessage);
                    throw new RuntimeException("GraphQL错误: " + errorMessage);
                }

                return responseJson;

            } catch (IOException | InterruptedException e) {
                logger.error("GraphQL查询执行失败", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("GraphQL查询执行失败", e);
            }
        });
    }

    /**
     * 同步执行GraphQL查询（用于DynamicTool）
     *
     * @param query GraphQL查询语句
     * @param variables 查询变量
     * @return 响应JSON节点
     */
    public JsonNode executeQuerySync(String query, Map<String, Object> variables) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("query", query);
            if (variables != null && !variables.isEmpty()) {
                ObjectNode varsNode = objectMapper.valueToTree(variables);
                requestBody.set("variables", varsNode);
            }

            String requestString = objectMapper.writeValueAsString(requestBody);
            logger.debug("发送GraphQL请求: {}", requestString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestString))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.debug("GraphQL响应: status={}, body={}", response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                logger.error("Linear API请求失败: status={}, body={}", response.statusCode(), response.body());
                return null;
            }

            JsonNode responseJson = objectMapper.readTree(response.body());

            // 检查GraphQL错误
            JsonNode errors = responseJson.path("errors");
            if (errors.isArray() && errors.size() > 0) {
                String errorMessage = errors.get(0).path("message").asText("Unknown error");
                logger.error("GraphQL错误: {}", errorMessage);
            }

            // 调试日志：打印mutation结果
            JsonNode data = responseJson.path("data");
            if (!data.isMissingNode()) {
                data.fields().forEachRemaining(entry -> {
                    JsonNode node = entry.getValue();
                    if (node.has("success")) {
                        logger.debug("Mutation result: {}.success={}", entry.getKey(), node.path("success").asBoolean());
                    }
                });
            }

            return responseJson;

        } catch (Exception e) {
            logger.error("同步GraphQL查询执行失败", e);
            return null;
        }
    }

    /**
     * 构建HTTP请求
     */
    private HttpRequest buildRequest(String query, ObjectNode variables) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("query", query);
            requestBody.set("variables", variables);

            return HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("构建请求失败", e);
        }
    }

    /**
     * 规范化Linear API响应为Issue对象
     *
     * @param node JSON节点
     * @return Issue对象
     */
    private Issue normalizeIssue(JsonNode node) {
        Issue.Builder builder = Issue.builder()
                .id(node.path("id").asText())
                .identifier(node.path("identifier").asText())
                .title(node.path("title").asText())
                .description(node.path("description").asText(null))
                .priority(node.path("priority").asInt(0))
                .state(node.path("state").path("name").asText(null))
                .branchName(node.path("branchName").asText(null))
                .url(node.path("url").asText(null))
                .createdAt(parseDateTime(node.path("createdAt").asText(null)))
                .updatedAt(parseDateTime(node.path("updatedAt").asText(null)));

        // assignee
        JsonNode assignee = node.path("assignee");
        if (!assignee.isMissingNode()) {
            builder.assigneeId(assignee.path("id").asText(null));
        }

        // labels
        List<String> labels = new ArrayList<>();
        JsonNode labelsNode = node.path("labels").path("nodes");
        if (labelsNode.isArray()) {
            for (JsonNode label : labelsNode) {
                String name = label.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    labels.add(name.trim().toLowerCase());
                }
            }
        }
        builder.labels(labels);

        // blockedBy - 查找inverseRelations中type为blocks的关系
        List<Issue.Blocker> blockers = new ArrayList<>();
        JsonNode relations = node.path("inverseRelations").path("nodes");
        if (relations.isArray()) {
            for (JsonNode relation : relations) {
                String type = relation.path("type").asText("");
                if ("blocks".equalsIgnoreCase(type.trim())) {
                    JsonNode issue = relation.path("issue");
                    if (!issue.isMissingNode()) {
                        blockers.add(new Issue.Blocker(
                                issue.path("id").asText(),
                                issue.path("identifier").asText(),
                                issue.path("state").path("name").asText()
                        ));
                    }
                }
            }
        }
        builder.blockedBy(blockers);

        return builder.build();
    }

    /**
     * 解析ISO 8601日期时间
     */
    private Instant parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            logger.warn("日期解析失败: {}", dateStr);
            return null;
        }
    }

    /**
     * 将列表分批
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * 按原始ID顺序排序
     */
    private List<Issue> sortByIdOrder(List<Issue> issues, List<String> idOrder) {
        return idOrder.stream()
                .map(id -> issues.stream()
                        .filter(issue -> id.equals(issue.getId()))
                        .findFirst()
                        .orElse(null))
                .filter(issue -> issue != null)
                .toList();
    }

    /**
     * 创建评论
     *
     * @param issueId 问题ID
     * @param body 评论内容
     * @return 是否成功
     */
    public CompletionStage<Boolean> createComment(String issueId, String body) {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("issueId", issueId);
        variables.put("body", body);

        return executeMutation(CREATE_COMMENT_MUTATION, variables)
                .thenApply(response -> {
                    Boolean success = response.path("data").path("commentCreate").path("success").asBoolean(false);
                    if (!success) {
                        logger.warn("创建评论失败: issueId={}", issueId);
                    }
                    return success;
                })
                .exceptionally(ex -> {
                    logger.error("创建评论异常: issueId={}", issueId, ex);
                    return false;
                });
    }

    /**
     * 更新问题状态
     *
     * @param issueId 问题ID
     * @param stateName 状态名称
     * @return 是否成功
     */
    public CompletionStage<Boolean> updateIssueState(String issueId, String stateName) {
        return resolveStateId(issueId, stateName)
                .thenCompose(stateId -> {
                    if (stateId == null) {
                        logger.warn("未找到状态: issueId={}, stateName={}", issueId, stateName);
                        return CompletableFuture.completedFuture(false);
                    }

                    ObjectNode variables = objectMapper.createObjectNode();
                    variables.put("issueId", issueId);
                    variables.put("stateId", stateId);

                    return executeMutation(UPDATE_ISSUE_STATE_MUTATION, variables)
                            .thenApply(response -> {
                                Boolean success = response.path("data").path("issueUpdate").path("success").asBoolean(false);
                                if (!success) {
                                    logger.warn("更新问题状态失败: issueId={}, stateName={}", issueId, stateName);
                                } else {
                                    logger.info("更新问题状态成功: issueId={}, stateName={}", issueId, stateName);
                                }
                                return success;
                            })
                            .exceptionally(ex -> {
                                logger.error("更新问题状态异常: issueId={}, stateName={}", issueId, stateName, ex);
                                return false;
                            });
                });
    }

    /**
     * 根据问题ID和状态名称解析状态ID
     */
    private CompletionStage<String> resolveStateId(String issueId, String stateName) {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("issueId", issueId);
        variables.put("stateName", stateName);

        return executeQuery(STATE_LOOKUP_QUERY, variables)
                .thenApply(response -> {
                    JsonNode nodes = response.path("data").path("issue").path("team").path("states").path("nodes");
                    if (nodes.isArray() && nodes.size() > 0) {
                        return nodes.get(0).path("id").asText(null);
                    }
                    return null;
                })
                .exceptionally(ex -> {
                    logger.error("解析状态ID异常: issueId={}, stateName={}", issueId, stateName, ex);
                    return null;
                });
    }

    /**
     * 执行 GraphQL mutation
     */
    private CompletionStage<JsonNode> executeMutation(String mutation, ObjectNode variables) {
        return executeQuery(mutation, variables);
    }

    /**
     * 通过 ID 获取 Issue 详情
     */
    @SuppressWarnings("unchecked")
    public JsonNode getIssueById(String issueId) {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("issueId", issueId);
        return executeQuerySync(GET_ISSUE_QUERY, (Map<String, Object>) objectMapper.convertValue(variables, Map.class));
    }

    /**
     * 通过 identifier 获取 Issue 详情
     */
    @SuppressWarnings("unchecked")
    public JsonNode getIssueByIdentifier(String identifier) {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("identifier", identifier);
        return executeQuerySync(GET_ISSUE_BY_IDENTIFIER_QUERY, (Map<String, Object>) objectMapper.convertValue(variables, Map.class));
    }

    /**
     * 获取 Issue 的评论列表
     */
    @SuppressWarnings("unchecked")
    public JsonNode getComments(String issueId) {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("issueId", issueId);
        return executeQuerySync(GET_COMMENTS_QUERY, (Map<String, Object>) objectMapper.convertValue(variables, Map.class));
    }
}
