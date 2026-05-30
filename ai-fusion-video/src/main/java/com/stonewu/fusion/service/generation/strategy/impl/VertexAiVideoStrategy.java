package com.stonewu.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Google Vertex AI (Veo) 视频生成策略
 * <p>
 * 通过 Vertex AI REST API 调用 Veo 模型进行视频生成（文生视频 / 图生视频）。
 * API 为异步模式：先提交生成请求获取 operation name，然后轮询 operation 状态直到完成。
 * <p>
 * ApiConfig 字段映射（与 VertexAiImageStrategy 一致）：
 * - appId: Google Cloud 项目 ID (Project ID)
 * - apiUrl: location (如 us-central1)，也可填写完整 URL 覆盖默认地址
 * - appSecret: 服务账号 JSON Key 内容（完整 JSON 字符串）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VertexAiVideoStrategy implements VideoGenerationStrategy {

    private static final String VERTEX_AI_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "veo-3.1-generate-preview";
    private static final int POLL_INTERVAL_SECONDS = 10;
    private static final int MAX_POLL_COUNT = 360;

    private final AiModelService aiModelService;
    private final ApiConfigService apiConfigService;
    private final VideoGenerationService videoGenerationService;
    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return "vertex_ai";
    }

    @Override
    public String submit(VideoTask task) {
        AiModel model = resolveModel(task);
        ApiConfig apiConfig = resolveApiConfig(model);
        String modelCode = (model != null && StrUtil.isNotBlank(model.getCode())) ? model.getCode() : DEFAULT_MODEL;

        String projectId = resolveProjectId(apiConfig);
        String location = resolveLocation(apiConfig);

        String endpoint = "global".equalsIgnoreCase(location)
                ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        String url = String.format(
                "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateVideo",
                endpoint, projectId, location, modelCode
        );

        String requestBody = buildRequestBody(task);

        log.info("[VertexAI Video] 提交视频生成: model={}, mode={}", modelCode, task.getGenerateMode());

        try {
            String accessToken = getAccessToken(apiConfig);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                    .build();

            OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "unknown";
                    throw new RuntimeException("Vertex AI 视频生成请求失败: HTTP " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                JsonNode root = OBJECT_MAPPER.readTree(responseBody);
                String operationName = root.path("name").asText(null);
                if (StrUtil.isBlank(operationName)) {
                    throw new RuntimeException("Vertex AI 返回中缺少 operation name");
                }

                log.info("[VertexAI Video] 任务已提交: operation={}", operationName);
                return operationName;
            }
        } catch (IOException e) {
            throw new RuntimeException("Vertex AI 视频生成调用异常: " + e.getMessage(), e);
        }
    }

    @Override
    public void poll(String platformTaskId, VideoTask task) {
        AiModel model = resolveModel(task);
        ApiConfig apiConfig = resolveApiConfig(model);

        String projectId = resolveProjectId(apiConfig);
        String location = resolveLocation(apiConfig);

        String endpoint = "global".equalsIgnoreCase(location)
                ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        // platformTaskId 是完整的 operation name，如 projects/.../locations/.../operations/xxx
        String pollUrl = String.format("https://%s/v1/%s", endpoint, platformTaskId);

        log.info("[VertexAI Video] 开始轮询: operation={}", platformTaskId);

        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);

        for (int i = 0; i < MAX_POLL_COUNT; i++) {
            try {
                String accessToken = getAccessToken(apiConfig);

                Request request = new Request.Builder()
                        .url(pollUrl)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "unknown";
                        throw new RuntimeException("Vertex AI 轮询失败: HTTP " + response.code() + " - " + errorBody);
                    }

                    String responseBody = response.body().string();
                    JsonNode root = OBJECT_MAPPER.readTree(responseBody);

                    boolean done = root.path("done").asBoolean(false);
                    if (done) {
                        JsonNode error = root.path("error");
                        if (!error.isMissingNode() && error.path("code").asInt(0) != 0) {
                            String errorMsg = error.path("message").asText("未知错误");
                            throw new RuntimeException("Vertex AI 视频生成失败: " + errorMsg);
                        }

                        handleSuccess(root, task);
                        log.info("[VertexAI Video] 视频生成完成: taskId={}", task.getTaskId());
                        return;
                    }
                }

                TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("视频生成轮询被中断", e);
            } catch (IOException e) {
                log.warn("[VertexAI Video] 轮询请求异常（将重试）: {}", e.getMessage());
                try {
                    TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("视频生成轮询被中断", ie);
                }
            }
        }

        throw new RuntimeException("Vertex AI 视频生成超时（轮询 " + MAX_POLL_COUNT + " 次）");
    }

    private String buildRequestBody(VideoTask task) {
        try {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();

            // instances
            ArrayNode instances = OBJECT_MAPPER.createArrayNode();
            ObjectNode instance = OBJECT_MAPPER.createObjectNode();
            if (StrUtil.isNotBlank(task.getPrompt())) {
                instance.put("prompt", task.getPrompt());
            }
            if (StrUtil.isNotBlank(task.getFirstFrameImageUrl())) {
                ObjectNode image = OBJECT_MAPPER.createObjectNode();
                image.put("imageUri", task.getFirstFrameImageUrl());
                instance.set("image", image);
            }
            instances.add(instance);
            root.set("instances", instances);

            // parameters
            ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
            if (StrUtil.isNotBlank(task.getRatio())) {
                parameters.put("aspectRatio", task.getRatio());
            }
            if (task.getDuration() != null && task.getDuration() > 0) {
                parameters.put("durationSeconds", task.getDuration());
            }
            if (task.getSeed() != null) {
                parameters.put("seed", task.getSeed());
            }
            if (task.getGenerateAudio() != null) {
                parameters.put("generateAudio", task.getGenerateAudio());
            }
            parameters.put("sampleCount", 1);
            root.set("parameters", parameters);

            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("构建 Vertex AI 视频请求体失败: " + e.getMessage(), e);
        }
    }

    private void handleSuccess(JsonNode operationRoot, VideoTask task) {
        JsonNode response = operationRoot.path("response");
        JsonNode videos = response.path("generatedSamples");
        if (videos.isMissingNode()) {
            videos = response.path("videos");
        }

        String videoUri = null;
        if (videos.isArray() && !videos.isEmpty()) {
            JsonNode first = videos.get(0);
            videoUri = first.path("video").path("uri").asText(null);
            if (videoUri == null) {
                videoUri = first.path("uri").asText(null);
            }
            if (videoUri == null) {
                videoUri = first.path("gcsUri").asText(null);
            }
        }

        if (StrUtil.isBlank(videoUri)) {
            throw new RuntimeException("Vertex AI 返回成功但未包含视频 URI");
        }

        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        if (!items.isEmpty()) {
            VideoItem item = items.get(0);
            item.setVideoUrl(videoUri);
            item.setStatus(1);
            videoGenerationService.updateItem(item);
        }

        task.setSuccessCount(1);
        videoGenerationService.update(task);
    }

    private String getAccessToken(ApiConfig apiConfig) throws IOException {
        if (StrUtil.isBlank(apiConfig.getAppSecret())) {
            throw new BusinessException("Vertex AI 配置缺少服务账号 JSON Key（appSecret 字段）");
        }

        var transportFactory = AiProxySupport.googleHttpTransportFactory(apiConfig);
        GoogleCredentials credentials = (transportFactory == null
                ? GoogleCredentials.fromStream(new ByteArrayInputStream(apiConfig.getAppSecret().getBytes(StandardCharsets.UTF_8)))
                : GoogleCredentials.fromStream(new ByteArrayInputStream(apiConfig.getAppSecret().getBytes(StandardCharsets.UTF_8)), transportFactory))
                .createScoped(Collections.singletonList(VERTEX_AI_SCOPE));
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private String resolveProjectId(ApiConfig apiConfig) {
        String projectId = apiConfig != null ? apiConfig.getAppId() : null;
        if (StrUtil.isNotBlank(projectId)) {
            return projectId;
        }
        projectId = apiConfig != null ? apiConfig.getApiKey() : null;
        if (StrUtil.isBlank(projectId)) {
            throw new BusinessException("Vertex AI 配置缺少 Project ID");
        }
        return projectId;
    }

    private String resolveLocation(ApiConfig apiConfig) {
        String location = apiConfig != null ? apiConfig.getApiUrl() : null;
        if (StrUtil.isNotBlank(location) && !isCustomUrl(location)) {
            return location;
        }
        return "us-central1";
    }

    private boolean isCustomUrl(String url) {
        return StrUtil.startWithIgnoreCase(url, "http://")
                || StrUtil.startWithIgnoreCase(url, "https://");
    }

    private AiModel resolveModel(VideoTask task) {
        if (task.getModelId() != null) {
            try {
                return aiModelService.getById(task.getModelId());
            } catch (Exception e) {
                log.warn("[VertexAI Video] 获取模型失败: modelId={}", task.getModelId());
            }
        }
        return null;
    }

    private ApiConfig resolveApiConfig(AiModel model) {
        if (model != null && model.getApiConfigId() != null) {
            try {
                ApiConfig config = apiConfigService.getById(model.getApiConfigId());
                if (config != null) {
                    return config;
                }
            } catch (Exception e) {
                log.warn("[VertexAI Video] 获取 API 配置失败: apiConfigId={}", model.getApiConfigId());
            }
        }
        throw new BusinessException("未找到 Vertex AI 视频生成 API 配置，请在系统设置中配置 vertex_ai 平台");
    }
}
