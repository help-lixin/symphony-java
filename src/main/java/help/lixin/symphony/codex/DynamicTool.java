package help.lixin.symphony.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import help.lixin.symphony.config.AppConfig;
import help.lixin.symphony.tracker.LinearClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Dynamic Tool executor for Codex app-server tool calls.
 *
 * 功能说明:
 * - 执行 Codex agent 请求的客户端工具
 * - 提供安全的、预定义的 Linear API 工具
 * - 将工具执行结果返回给 Codex
 *
 * 提供的工具:
 * - get_issue: 获取 Issue 详情（通过 ID 或 identifier）
 * - get_comments: 获取 Issue 的评论列表
 * - create_comment: 创建评论
 * - update_issue_state: 更新 Issue 状态
 */
public class DynamicTool {
    private static final Logger logger = LoggerFactory.getLogger(DynamicTool.class);

    private static final String TOOL_GET_ISSUE = "get_issue";
    private static final String TOOL_GET_COMMENTS = "get_comments";
    private static final String TOOL_CREATE_COMMENT = "create_comment";
    private static final String TOOL_UPDATE_ISSUE_STATE = "update_issue_state";

    private final LinearClient linearClient;
    private final ObjectMapper objectMapper;

    public DynamicTool(LinearClient linearClient) {
        this.linearClient = linearClient;
        this.objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    /**
     * 执行工具
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 执行结果
     */
    public Map<String, Object> execute(String toolName, Object arguments) {
        return switch (toolName) {
            case TOOL_GET_ISSUE -> getIssue(arguments);
            case TOOL_GET_COMMENTS -> getComments(arguments);
            case TOOL_CREATE_COMMENT -> createComment(arguments);
            case TOOL_UPDATE_ISSUE_STATE -> updateIssueState(arguments);
            default -> failureResponse("Unsupported dynamic tool: " + toolName +
                    ". Supported tools: " + supportedToolNames());
        };
    }

    /**
     * 获取工具规格列表
     */
    public static List<Map<String, Object>> toolSpecs() {
        return List.of(
                // get_issue 工具
                Map.of(
                        "name", TOOL_GET_ISSUE,
                        "description", "Get issue details from Linear. Use this to fetch full issue information including title, description, state, labels, and blocker relationships.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "properties", Map.of(
                                        "id", Map.of(
                                                "type", "string",
                                                "description", "The issue ID (e.g., 'abc123'). Use either this or identifier, not both."
                                        ),
                                        "identifier", Map.of(
                                                "type", "string",
                                                "description", "The issue identifier (e.g., 'HEL-6'). Use either this or id, not both."
                                        )
                                )
                        )
                ),
                // get_comments 工具
                Map.of(
                        "name", TOOL_GET_COMMENTS,
                        "description", "Get comments for an issue from Linear. Returns the list of comments with their content, author, and timestamps.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("issueId"),
                                "properties", Map.of(
                                        "issueId", Map.of(
                                                "type", "string",
                                                "description", "The issue ID to get comments for."
                                        )
                                )
                        )
                ),
                // create_comment 工具
                Map.of(
                        "name", TOOL_CREATE_COMMENT,
                        "description", "Create a comment on an issue in Linear. Use this to add notes, updates, or responses to an issue.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("issueId", "body"),
                                "properties", Map.of(
                                        "issueId", Map.of(
                                                "type", "string",
                                                "description", "The issue ID to create the comment on."
                                        ),
                                        "body", Map.of(
                                                "type", "string",
                                                "description", "The comment content in Markdown format."
                                        )
                                )
                        )
                ),
                // update_issue_state 工具
                Map.of(
                        "name", TOOL_UPDATE_ISSUE_STATE,
                        "description", "Update an issue's state in Linear. Use this to move issues through workflow states like Todo, In Progress, Done, etc.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("issueId", "stateName"),
                                "properties", Map.of(
                                        "issueId", Map.of(
                                                "type", "string",
                                                "description", "The issue ID to update."
                                        ),
                                        "stateName", Map.of(
                                                "type", "string",
                                                "description", "The target state name (e.g., 'Done', 'In Progress', 'Todo', 'Cancelled')."
                                        )
                                )
                        )
                )
        );
    }

    /**
     * 获取 Issue 详情
     */
    private Map<String, Object> getIssue(Object arguments) {
        try {
            String id = extractString(arguments, "id");
            String identifier = extractString(arguments, "identifier");

            JsonNode response;
            if (id != null && !id.isBlank()) {
                response = linearClient.getIssueById(id);
            } else if (identifier != null && !identifier.isBlank()) {
                response = linearClient.getIssueByIdentifier(identifier);
            } else {
                return failureResponse("get_issue requires either 'id' or 'identifier' argument");
            }

            return handleResponse(response);

        } catch (Exception e) {
            logger.error("get_issue tool execution failed", e);
            return failureResponse("get_issue failed: " + e.getMessage());
        }
    }

    /**
     * 获取 Issue 的评论列表
     */
    private Map<String, Object> getComments(Object arguments) {
        try {
            String issueId = extractString(arguments, "issueId");
            if (issueId == null || issueId.isBlank()) {
                return failureResponse("get_comments requires 'issueId' argument");
            }

            JsonNode response = linearClient.getComments(issueId);
            return handleResponse(response);

        } catch (Exception e) {
            logger.error("get_comments tool execution failed", e);
            return failureResponse("get_comments failed: " + e.getMessage());
        }
    }

    /**
     * 创建评论
     */
    private Map<String, Object> createComment(Object arguments) {
        try {
            String issueId = extractString(arguments, "issueId");
            String body = extractString(arguments, "body");

            if (issueId == null || issueId.isBlank()) {
                return failureResponse("create_comment requires 'issueId' argument");
            }
            if (body == null || body.isBlank()) {
                return failureResponse("create_comment requires 'body' argument");
            }

            Boolean success = linearClient.createComment(issueId, body).toCompletableFuture().join();
            if (success) {
                return successResponse(objectMapper.createObjectNode()
                        .put("success", true)
                        .put("message", "Comment created successfully")
                        .toString());
            } else {
                return failureResponse("Failed to create comment");
            }

        } catch (Exception e) {
            logger.error("create_comment tool execution failed", e);
            return failureResponse("create_comment failed: " + e.getMessage());
        }
    }

    /**
     * 更新 Issue 状态
     */
    private Map<String, Object> updateIssueState(Object arguments) {
        try {
            String issueId = extractString(arguments, "issueId");
            String stateName = extractString(arguments, "stateName");

            if (issueId == null || issueId.isBlank()) {
                return failureResponse("update_issue_state requires 'issueId' argument");
            }
            if (stateName == null || stateName.isBlank()) {
                return failureResponse("update_issue_state requires 'stateName' argument");
            }

            Boolean success = linearClient.updateIssueState(issueId, stateName).toCompletableFuture().join();
            if (success) {
                return successResponse(objectMapper.createObjectNode()
                        .put("success", true)
                        .put("message", "Issue state updated to '" + stateName + "'")
                        .toString());
            } else {
                return failureResponse("Failed to update issue state. Check if the state name is valid.");
            }

        } catch (Exception e) {
            logger.error("update_issue_state tool execution failed", e);
            return failureResponse("update_issue_state failed: " + e.getMessage());
        }
    }

    /**
     * 处理 GraphQL 响应
     */
    private Map<String, Object> handleResponse(JsonNode response) {
        if (response == null) {
            return failureResponse("Linear API request failed: null response");
        }

        JsonNode errors = response.path("errors");
        if (errors.isArray() && errors.size() > 0) {
            String errorMessage = errors.get(0).path("message").asText("Unknown error");
            return failureResponse("Linear API error: " + errorMessage);
        }

        return successResponse(response.toString());
    }

    /**
     * 从参数中提取字符串
     */
    private String extractString(Object arguments, String key) {
        if (arguments == null) {
            return null;
        }

        if (arguments instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> argsMap = (Map<String, Object>) arguments;
            Object value = argsMap.get(key);
            if (value instanceof String) {
                return ((String) value).trim();
            }
        }

        if (arguments instanceof JsonNode) {
            JsonNode node = ((JsonNode) arguments).path(key);
            if (node.isTextual()) {
                return node.asText().trim();
            }
        }

        return null;
    }

    /**
     * 构建成功响应
     */
    private Map<String, Object> successResponse(String output) {
        return Map.of(
                "success", true,
                "output", output,
                "contentItems", List.of(Map.of(
                        "type", "inputText",
                        "text", output
                ))
        );
    }

    /**
     * 构建失败响应
     */
    private Map<String, Object> failureResponse(String errorMessage) {
        return Map.of(
                "success", false,
                "output", errorMessage,
                "contentItems", List.of(Map.of(
                        "type", "inputText",
                        "text", errorMessage
                ))
        );
    }

    /**
     * 获取支持的工具名称列表
     */
    private String supportedToolNames() {
        return String.join(", ", TOOL_GET_ISSUE, TOOL_GET_COMMENTS, TOOL_CREATE_COMMENT, TOOL_UPDATE_ISSUE_STATE);
    }
}
