/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.burst;

import com.alibaba.cloud.ai.dataagent.bo.schema.ReferencePreviewBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ReferenceTargetBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSectionBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.dto.burst.BurstAnalysisResponseDTO;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager.BurstAnalysisContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager.ValveRef;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.QueryResultContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.ReferenceTarget;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.SectionContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ReferenceResolutionContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ReferenceResolutionContextManager.ReferenceContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService.SectionSemanticReferenceContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService.SessionSemanticReferenceContext;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP-backed burst-analysis service.
 */
@Slf4j
@Service
public class BurstAnalysisServiceImpl implements BurstAnalysisService {

    private static final int PIPE_LAYER_ID = 0;

    private static final int MAX_RAW_RESPONSE_LENGTH = 12000;

    private static final Pattern LAYER_ID_PATTERN =
            Pattern.compile("(?i)layerId\\s*(?:[:=\\uFF1A])\\s*'?([\\w-]+)'?");

    private static final Pattern GID_PATTERN =
            Pattern.compile("(?i)gid\\s*(?:[:=\\uFF1A])\\s*'?([\\w-]+)'?");

    private static final Pattern CLOSE_VALVES_PATTERN =
            Pattern.compile("(?i)closeValves\\s*(?:[:=\\uFF1A])\\s*'?([\\w,\\-]*)'?");

    private static final Pattern PARENT_ANALYSIS_ID_PATTERN =
            Pattern.compile("(?i)parentAnalysisId\\s*(?:[:=\\uFF1A])\\s*'?([\\w-]+)'?");

    private static final Pattern ORDINAL_PATTERN =
            Pattern.compile("\u7B2C\\s*([\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341\u767E0-9\\s]+)\\s*[\u6761\u4E2A\u6839]?");

    private static final Pattern DIAMETER_PATTERN =
            Pattern.compile("\u7BA1\u5F84\\s*(?:\u4E3A|\u662F|=|\u7B49\u4E8E)?\\s*(\\d+(?:\\.\\d+)?)");

    private static final Pattern LENGTH_PATTERN =
            Pattern.compile("\u7BA1\u957F\\s*(?:\u4E3A|\u662F|=|\u7B49\u4E8E)?\\s*(\\d+(?:\\.\\d+)?)");

    private static final List<String> PIPE_REFERENCE_KEYWORDS = List.of("\u7BA1\u7EBF", "\u7BA1\u9053",
            "\u7BA1\u6BB5", "\u7BA1\u5B50", "\u8FD9\u6761", "\u8FD9\u6839", "\u90A3\u6761", "\u90A3\u6839", "\u8BE5\u7BA1\u7EBF", "\u4E0A\u4E00\u6761",
            "\u4E0A\u4E00\u6839");

    private static final List<String> VALVE_REFERENCE_KEYWORDS = List.of("\u9600\u95E8", "\u5173\u9600");

    private static final List<String> REANALYZE_KEYWORDS = List.of("\u91CD\u65B0\u5206\u6790",
            "\u4E8C\u6B21\u5206\u6790", "\u4E8C\u6B21\u5173\u9600", "\u5931\u6548",
            "\u91CD\u65B0\u5173\u9600");

    private static final List<String> CLOSE_ACTION_KEYWORDS = List.of("\u5173\u95ED", "\u5173\u6389",
            "\u6267\u884C\u5173\u9600", "\u6A21\u62DF\u5173\u9600");

    private static final List<String> VALVE_INFO_QUERY_KEYWORDS = List.of("\u67E5\u8BE2", "\u5217\u51FA", "\u663E\u793A",
            "\u4FE1\u606F", "\u8BE6\u60C5", "\u8BE6\u7EC6", "\u6709\u54EA\u4E9B", "\u662F\u54EA", "\u54EA\u4E9B",
            "\u54EA\u51E0\u4E2A", "\u54EA2\u4E2A");

    private static final List<String> QUERY_RESULT_REFERENCE_KEYWORDS = List.of("\u6570\u636E", "\u7ED3\u679C",
            "\u8BB0\u5F55", "\u7B2C\u4E00\u6761", "\u7B2C\u4E00\u4E2A", "\u7B2C\u4E00\u6839", "\u8FD9\u6761",
            "\u8FD9\u6839", "\u8FD9\u4E2A", "\u90A3\u6761", "\u90A3\u6839", "\u521A\u624D\u90A3\u6839");

    private static final List<String> PIPE_ATTRIBUTE_KEYWORDS = List.of("\u7BA1\u5F84", "\u7BA1\u957F", "\u7BA1\u6750",
            "\u6750\u8D28");

    private static final String BURST_POINT = "\u7206\u7BA1\u70B9";

    private static final List<String> AFFECTED_USER_COUNT_FIELDS = List.of("users_affected", "affected_users",
            "affected_user_count", "user_count", "total_users_affected", "total_affected_users", "impact_users",
            "impacted_users", "affectedConsumers", "affected_consumer_count");

    private final WebClient.Builder webClientBuilder;

    private final DataAgentProperties dataAgentProperties;

    private final BurstAnalysisContextManager burstAnalysisContextManager;

    private final QueryResultContextManager queryResultContextManager;

    private final ReferenceResolutionContextManager referenceResolutionContextManager;

    private final SessionSemanticReferenceContextService sessionSemanticReferenceContextService;

    public BurstAnalysisServiceImpl(WebClient.Builder webClientBuilder, DataAgentProperties dataAgentProperties,
                                    BurstAnalysisContextManager burstAnalysisContextManager,
                                    QueryResultContextManager queryResultContextManager,
                                    ReferenceResolutionContextManager referenceResolutionContextManager,
                                    SessionSemanticReferenceContextService sessionSemanticReferenceContextService) {
        this.webClientBuilder = webClientBuilder;
        this.dataAgentProperties = dataAgentProperties;
        this.burstAnalysisContextManager = burstAnalysisContextManager;
        this.queryResultContextManager = queryResultContextManager;
        this.referenceResolutionContextManager = referenceResolutionContextManager;
        this.sessionSemanticReferenceContextService = sessionSemanticReferenceContextService;
    }

    @Override
    public BurstAnalysisResponseDTO analyze(String query, String multiTurnContext, String routeReason,
                                            String agentId, String threadId, String sessionId) {
        DataAgentProperties.BurstAnalysis properties = dataAgentProperties.getBurstAnalysis();
        if (!properties.isEnabled()) {
            return buildFailure("Burst-analysis integration is disabled by configuration.");
        }
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            return buildFailure("Burst-analysis baseUrl is not configured.");
        }

        PipeAnalysisRequest request = extractRequest(query, multiTurnContext, threadId, sessionId);
        if (StringUtils.isBlank(request.layerId()) || StringUtils.isBlank(request.gid())) {
            String clarificationSummary = buildClarificationSummary(query, threadId, sessionId);
            return BurstAnalysisResponseDTO.builder()
                    .success(false)
                    .summary(clarificationSummary)
                    .layerId(StringUtils.defaultString(request.layerId()))
                    .gid(StringUtils.defaultString(request.gid()))
                    .closeValves(StringUtils.defaultString(request.closeValves()))
                    .parentAnalysisId(StringUtils.defaultString(request.parentAnalysisId()))
                    .build();
        }

        String requestUri = buildRequestUri(properties, request);
        try {
            byte[] responseBytes = webClientBuilder.build()
                    .get()
                    .uri(requestUri)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
            String responseBody = responseBytes == null ? "" : new String(responseBytes, StandardCharsets.UTF_8);
            log.info("Burst-analysis API call succeeded for threadId={}, agentId={}, uri={}", threadId, agentId,
                    requestUri);
            return buildSuccessResponse(request, requestUri, responseBody, routeReason, threadId, sessionId);
        } catch (Exception ex) {
            log.error("Burst-analysis API call failed for threadId={}, uri={}", threadId, requestUri, ex);
            return BurstAnalysisResponseDTO.builder()
                    .success(false)
                    .summary("Burst-analysis API call failed: " + ex.getMessage())
                    .layerId(request.layerId())
                    .gid(request.gid())
                    .closeValves(StringUtils.defaultString(request.closeValves()))
                    .parentAnalysisId(StringUtils.defaultString(request.parentAnalysisId()))
                    .requestUri(requestUri)
                    .highlights(List.of())
                    .build();
        }
    }

    private String buildRequestUri(DataAgentProperties.BurstAnalysis properties, PipeAnalysisRequest request) {
        String baseUrl = StringUtils.removeEnd(properties.getBaseUrl(), "/");
        String path = properties.getPipeBurstPath();
        String url = path.startsWith("/") ? baseUrl + path : baseUrl + "/" + path;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                .queryParam("layerId", request.layerId())
                .queryParam("summary", true)
                .queryParam("gid", request.gid());
        if (StringUtils.isNotBlank(request.closeValves())) {
            builder.queryParam("closeValves", request.closeValves());
        }
        if (StringUtils.isNotBlank(request.parentAnalysisId())) {
            builder.queryParam("parentAnalysisId", request.parentAnalysisId());
        }
        return builder.encode().build().toUriString();
    }

    private PipeAnalysisRequest extractRequest(String query, String multiTurnContext, String threadId,
                                               String sessionId) {
        String explicitSource = StringUtils.defaultString(query);
        PipeAnalysisRequest explicitRequest = new PipeAnalysisRequest(extract(explicitSource, LAYER_ID_PATTERN),
                extract(explicitSource, GID_PATTERN),
                emptyToNull(extract(explicitSource, CLOSE_VALVES_PATTERN)),
                emptyToNull(extract(explicitSource, PARENT_ANALYSIS_ID_PATTERN)));
        log.info(
                "[CTX_TRACE][BURST_RESOLVE][SOURCE][threadId={}][sessionId={}] explicitQueryGid={} explicitQueryLayerId={} multiTurnContainsGid={}",
                threadId, sessionId, StringUtils.defaultString(explicitRequest.gid()),
                StringUtils.defaultString(explicitRequest.layerId()),
                StringUtils.isNotBlank(extract(StringUtils.defaultString(multiTurnContext), GID_PATTERN)));
        if (StringUtils.isNotBlank(explicitRequest.gid())) {
            return new PipeAnalysisRequest(normalizeLayerId(explicitRequest.layerId(), "pipe"), explicitRequest.gid(),
                    explicitRequest.closeValves(), explicitRequest.parentAnalysisId());
        }
        return resolveRequestFromContext(query, explicitRequest, threadId, sessionId);
    }

    private PipeAnalysisRequest resolveRequestFromContext(String query, PipeAnalysisRequest explicitRequest,
                                                          String threadId, String sessionId) {
        String normalized = StringUtils.defaultString(query).trim();
        Integer ordinal = parseOrdinal(normalized);
        BurstAnalysisContext context = burstAnalysisContextManager.get(threadId);
        PipeAnalysisRequest burstValveActionRequest = resolveValveActionFromBurstContext(normalized, ordinal,
                explicitRequest, context);
        if (burstValveActionRequest != explicitRequest) {
            return burstValveActionRequest;
        }

        PipeAnalysisRequest queryResultRequest = resolveRequestFromLatestQueryResult(normalized, ordinal, explicitRequest,
                threadId, sessionId);
        if (queryResultRequest != explicitRequest) {
            return queryResultRequest;
        }

        if (context == null) {
            return explicitRequest;
        }

        if (containsAny(normalized, PIPE_REFERENCE_KEYWORDS)) {
            String gid = resolvePipeGid(context, ordinal);
            if (StringUtils.isNotBlank(gid)) {
                return new PipeAnalysisRequest(String.valueOf(PIPE_LAYER_ID), gid, explicitRequest.closeValves(),
                        explicitRequest.parentAnalysisId());
            }
        }

        if (normalized.contains(BURST_POINT) && StringUtils.isNotBlank(context.sourceLayerId())
                && StringUtils.isNotBlank(context.sourceGid())) {
            return new PipeAnalysisRequest(context.sourceLayerId(), context.sourceGid(), explicitRequest.closeValves(),
                    StringUtils.defaultIfBlank(explicitRequest.parentAnalysisId(), context.analysisId()));
        }

        return explicitRequest;
    }

    private PipeAnalysisRequest resolveValveActionFromBurstContext(String normalizedQuery, Integer ordinal,
                                                                   PipeAnalysisRequest explicitRequest,
                                                                   BurstAnalysisContext context) {
        if (context == null || !containsAny(normalizedQuery, VALVE_REFERENCE_KEYWORDS) || !isValveActionRequest(normalizedQuery)) {
            return explicitRequest;
        }
        String closeValveId = resolveValveId(context, ordinal);
        if (StringUtils.isBlank(closeValveId) || StringUtils.isBlank(context.sourceLayerId())
                || StringUtils.isBlank(context.sourceGid())) {
            return explicitRequest;
        }
        String closeValves = mergeCloseValves(explicitRequest.closeValves(), closeValveId);
        String parentAnalysisId = StringUtils.defaultIfBlank(explicitRequest.parentAnalysisId(), context.analysisId());
        return new PipeAnalysisRequest(context.sourceLayerId(), context.sourceGid(), closeValves, parentAnalysisId);
    }

    private String buildClarificationSummary(String query, String threadId, String sessionId) {
        SessionSemanticReferenceContext sessionContext = sessionSemanticReferenceContextService.resolve(sessionId);
        QueryResultContext queryResultContext = queryResultContextManager.get(threadId);
        boolean hasSessionTargets = hasUsableSessionContext(sessionContext);
        boolean hasThreadTargets = hasUsableResultContext(queryResultContext);
        if (!hasSessionTargets && !hasThreadTargets) {
            return "\u5F53\u524D\u4F1A\u8BDD\u8FD8\u6CA1\u6709\u53EF\u5F15\u7528\u7684\u7BA1\u6BB5\u7ED3\u679C\u3002\u8BF7\u5148\u6267\u884C\u4E00\u6B21\u76F8\u5173\u7684\u7BA1\u7EBF\u67E5\u8BE2\uFF0C\u518D\u57FA\u4E8E\u7ED3\u679C\u8FDB\u884C\u7206\u7BA1\u5206\u6790\u3002";
        }
        return "\u5DF2\u627E\u5230\u4E0A\u4E00\u8F6E\u5019\u9009\u7BA1\u6BB5\uFF0C\u4F46\u6309\u5F53\u524D\u8BED\u4E49\u6761\u4EF6\u65E0\u6CD5\u552F\u4E00\u5B9A\u4F4D\u76EE\u6807\u3002\u8BF7\u8865\u5145\u66F4\u660E\u786E\u7684\u7BA1\u5F84\u3001\u7BA1\u6750\u6216\u5E8F\u53F7\u4FE1\u606F\u3002";
    }

    private PipeAnalysisRequest resolveRequestFromLatestQueryResult(String normalizedQuery, Integer ordinal,
                                                                    PipeAnalysisRequest explicitRequest, String threadId, String sessionId) {
        ReferenceContext referenceContext = referenceResolutionContextManager.get(threadId);
        SessionSemanticReferenceContext sessionContext = sessionSemanticReferenceContextService.resolve(sessionId);
        PipeAnalysisRequest resolvedFromSessionContext = resolveRequestFromSessionContext(sessionContext, normalizedQuery,
                ordinal, explicitRequest, threadId, sessionId, referenceContext);
        if (resolvedFromSessionContext != explicitRequest) {
            return resolvedFromSessionContext;
        }

        QueryResultContext resultContext = queryResultContextManager.get(threadId);
        log.info("[CTX_TRACE][BURST_REF][LOAD_CONTEXT][threadId={}] ordinal={} query={} context={}", threadId, ordinal,
                normalizedQuery, summarizeQueryResultContext(resultContext));
        PipeAnalysisRequest resolvedFromThreadContext = resolveRequestFromQueryResultContext(resultContext, normalizedQuery,
                ordinal, explicitRequest, threadId, referenceContext);
        if (resolvedFromThreadContext != explicitRequest) {
            return resolvedFromThreadContext;
        }

        log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}][sessionId={}] reason=no_usable_query_result_context",
                threadId, sessionId);
        return explicitRequest;
    }

    private PipeAnalysisRequest resolveRequestFromQueryResultContext(QueryResultContext resultContext,
                                                                     String normalizedQuery, Integer ordinal, PipeAnalysisRequest explicitRequest, String threadId,
                                                                     ReferenceContext referenceContext) {
        if (resultContext == null || !hasUsableResultContext(resultContext)) {
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=no_query_result_context", threadId);
            return explicitRequest;
        }
        String entityType = resultContext.entityType();
        if (referenceContext != null && StringUtils.isNotBlank(referenceContext.entityType())) {
            entityType = referenceContext.entityType();
        }
        entityType = inferEntityType(resultContext, entityType, normalizedQuery);
        log.info("[CTX_TRACE][BURST_REF][ENTITY_TYPE][threadId={}] inferredEntityType={} referenceContextEntityType={}",
                threadId, entityType,
                referenceContext == null ? "" : StringUtils.defaultString(referenceContext.entityType()));
        SectionContext selectedSection = selectResultSection(resultContext, entityType);
        List<ReferenceTarget> sectionTargets = sectionReferenceTargets(resultContext, selectedSection);
        List<Map<String, String>> sectionRows = sectionRows(resultContext, selectedSection);

        if (!looksLikeLatestResultReference(normalizedQuery, ordinal, entityType)) {
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=query_not_matched_for_result_reference", threadId);
            return explicitRequest;
        }

        SemanticReferenceCriteria criteria = buildSemanticReferenceCriteria(normalizedQuery, ordinal, entityType,
                sectionTargets);
        log.info("[CTX_TRACE][BURST_RESOLVE][FILTER][threadId={}] criteria={} targetCount={}", threadId, criteria,
                sectionTargets == null ? 0 : sectionTargets.size());
        ReferenceTarget targetReference = resolveSemanticTargetReference(sectionTargets, criteria, normalizedQuery);
        if (targetReference != null && StringUtils.isNotBlank(targetReference.gid())) {
            String layerId = normalizeLayerId(targetReference.layerId(), targetReference.entityType());
            if (StringUtils.isNotBlank(layerId)) {
                log.info(
                        "[CTX_TRACE][BURST_REF][HIT_TARGET][threadId={}] criteria={} entityType={} gid={} layerId={} target={}",
                        threadId, criteria, StringUtils.defaultIfBlank(targetReference.entityType(), entityType),
                        targetReference.gid(), layerId, targetReference);
                return new PipeAnalysisRequest(layerId, targetReference.gid(), explicitRequest.closeValves(),
                        explicitRequest.parentAnalysisId());
            }
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=target_reference_layerId_missing target={}",
                    threadId, targetReference);
        }

        Map<String, String> targetRow = resolveTargetRow(sectionRows, ordinal);
        if (targetRow == null || targetRow.isEmpty()) {
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=target_row_empty", threadId);
            return explicitRequest;
        }
        log.info("[CTX_TRACE][BURST_REF][TARGET_ROW][threadId={}] row={}", threadId,
                StringUtils.abbreviate(String.valueOf(targetRow), 2000));

        String gid = extractValue(targetRow, "gid", "pipe_gid", "valve_gid", "feature_gid", "objectid", "id");
        if (StringUtils.isBlank(gid)) {
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=gid_missing", threadId);
            return explicitRequest;
        }

        String layerId = extractValue(targetRow, "layerid", "layer_id", "layerId");
        layerId = normalizeLayerId(layerId, entityType);
        if (StringUtils.isBlank(layerId)) {
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=layerId_missing", threadId);
            return explicitRequest;
        }

        log.info(
                "[CTX_TRACE][BURST_REF][HIT][threadId={}] entityType={} gid={} layerId={} closeValves={} parentAnalysisId={}",
                threadId, entityType, gid, layerId, StringUtils.defaultString(explicitRequest.closeValves()),
                StringUtils.defaultString(explicitRequest.parentAnalysisId()));
        return new PipeAnalysisRequest(layerId, gid, explicitRequest.closeValves(), explicitRequest.parentAnalysisId());
    }

    private PipeAnalysisRequest resolveRequestFromSessionContext(SessionSemanticReferenceContext sessionContext,
                                                                 String normalizedQuery, Integer ordinal, PipeAnalysisRequest explicitRequest, String threadId, String sessionId,
                                                                 ReferenceContext referenceContext) {
        log.info("[CTX_TRACE][BURST_REF][LOAD_SESSION_CONTEXT][threadId={}][sessionId={}] ordinal={} query={} context={}",
                threadId, sessionId, ordinal, normalizedQuery, summarizeSessionContext(sessionContext));
        if (sessionContext == null || !hasUsableSessionContext(sessionContext)) {
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}][sessionId={}] reason=no_session_semantic_context",
                    threadId, sessionId);
            return explicitRequest;
        }
        String entityType = sessionContext.entityType();
        if (referenceContext != null && StringUtils.isNotBlank(referenceContext.entityType())) {
            entityType = referenceContext.entityType();
        }
        entityType = inferEntityType(entityType, normalizedQuery);
        if (!looksLikeLatestResultReference(normalizedQuery, ordinal, entityType)) {
            log.info(
                    "[CTX_TRACE][BURST_REF][MISS][threadId={}][sessionId={}] reason=session_context_query_not_matched",
                    threadId, sessionId);
            return explicitRequest;
        }
        SectionSemanticReferenceContext selectedSection = selectSessionSection(sessionContext, entityType);
        List<ReferenceTarget> sectionTargets = sectionReferenceTargets(sessionContext, selectedSection);
        SemanticReferenceCriteria criteria = buildSemanticReferenceCriteria(normalizedQuery, ordinal, entityType,
                sectionTargets);
        log.info("[CTX_TRACE][BURST_RESOLVE][FILTER][threadId={}][sessionId={}] criteria={} targetCount={}", threadId,
                sessionId, criteria, sectionTargets == null ? 0 : sectionTargets.size());
        ReferenceTarget targetReference = resolveSemanticTargetReference(sectionTargets, criteria, normalizedQuery);
        if (targetReference == null || StringUtils.isBlank(targetReference.gid())) {
            log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}][sessionId={}] reason=session_target_missing", threadId,
                    sessionId);
            return explicitRequest;
        }
        String layerId = normalizeLayerId(targetReference.layerId(),
                StringUtils.defaultIfBlank(targetReference.entityType(), entityType));
        log.info(
                "[CTX_TRACE][BURST_REF][HIT_SESSION][threadId={}][sessionId={}] criteria={} entityType={} gid={} layerId={} target={}",
                threadId, sessionId, criteria, StringUtils.defaultIfBlank(targetReference.entityType(), entityType),
                targetReference.gid(), layerId, targetReference);
        return new PipeAnalysisRequest(layerId, targetReference.gid(), explicitRequest.closeValves(),
                explicitRequest.parentAnalysisId());
    }

    private ReferenceTarget resolveTargetReference(QueryResultContext context, Integer ordinal) {
        return resolveTargetReference(context.referenceTargets(), ordinal);
    }

    private ReferenceTarget resolveTargetReference(List<ReferenceTarget> referenceTargets, Integer ordinal) {
        if (referenceTargets == null || referenceTargets.isEmpty()) {
            return null;
        }
        if (ordinal == null || ordinal <= 0) {
            return referenceTargets.get(0);
        }
        if (ordinal > referenceTargets.size()) {
            return null;
        }
        return referenceTargets.get(ordinal - 1);
    }

    private ReferenceTarget resolveSemanticTargetReference(List<ReferenceTarget> referenceTargets,
                                                           SemanticReferenceCriteria criteria, String normalizedQuery) {
        if (referenceTargets == null || referenceTargets.isEmpty()) {
            return null;
        }
        if (criteria == null) {
            return resolveTargetReference(referenceTargets, null);
        }
        List<ReferenceTarget> filteredTargets = filterTargets(referenceTargets, criteria, normalizedQuery);
        log.info("[CTX_TRACE][BURST_RESOLVE][FILTER] criteria={} filteredCount={} filteredTargets={}", criteria,
                filteredTargets.size(), StringUtils.abbreviate(String.valueOf(filteredTargets), 2000));
        if (filteredTargets.isEmpty()) {
            return null;
        }
        if (filteredTargets.size() == 1) {
            log.info("[CTX_TRACE][BURST_RESOLVE][MATCH] uniqueFilteredMatch criteria={} target={}", criteria,
                    filteredTargets.get(0));
            return filteredTargets.get(0);
        }
        if (criteria.ordinalWithinFilteredCandidates() != null) {
            return resolveTargetReference(filteredTargets, criteria.ordinalWithinFilteredCandidates());
        }
        if (criteria.hasStrongAttributeFilters()) {
            log.info("[CTX_TRACE][BURST_RESOLVE][MATCH] ambiguousFilteredMatch criteria={} candidates={}", criteria,
                    StringUtils.abbreviate(String.valueOf(filteredTargets), 2000));
            return null;
        }
        List<ScoredReferenceTarget> scoredTargets = new ArrayList<>();
        for (ReferenceTarget target : filteredTargets) {
            int score = scoreTarget(target, criteria, normalizedQuery);
            if (score >= 0) {
                scoredTargets.add(new ScoredReferenceTarget(target, score));
            }
        }
        if (scoredTargets.isEmpty()) {
            return null;
        }
        scoredTargets.sort((left, right) -> Integer.compare(right.score(), left.score()));
        ScoredReferenceTarget best = scoredTargets.get(0);
        if (best.score() <= 0) {
            if (criteria.ordinalWithinFilteredCandidates() != null
                    && criteria.ordinalWithinFilteredCandidates() > 0) {
                return resolveTargetReference(filteredTargets, criteria.ordinalWithinFilteredCandidates());
            }
            return filteredTargets.size() == 1 ? filteredTargets.get(0) : null;
        }
        if (scoredTargets.size() > 1 && best.score() == scoredTargets.get(1).score()) {
            log.info("[CTX_TRACE][BURST_RESOLVE][MATCH] ambiguousMatch criteria={} candidates={}", criteria,
                    scoredTargets.stream().limit(3).toList());
            return null;
        }
        log.info("[CTX_TRACE][BURST_RESOLVE][MATCH] matched criteria={} target={} score={}", criteria, best.target(),
                best.score());
        return best.target();
    }

    private List<ReferenceTarget> filterTargets(List<ReferenceTarget> referenceTargets, SemanticReferenceCriteria criteria,
                                                String normalizedQuery) {
        List<ReferenceTarget> filteredTargets = new ArrayList<>();
        for (ReferenceTarget target : referenceTargets) {
            if (matchesTargetFilters(target, criteria, normalizedQuery)) {
                filteredTargets.add(target);
            }
        }
        return filteredTargets;
    }

    private boolean matchesTargetFilters(ReferenceTarget target, SemanticReferenceCriteria criteria,
                                         String normalizedQuery) {
        if (target == null) {
            return false;
        }
        String targetEntityType = StringUtils.defaultIfBlank(target.entityType(), criteria.entityType());
        if (StringUtils.isNotBlank(criteria.entityType()) && StringUtils.isNotBlank(targetEntityType)
                && !StringUtils.equalsIgnoreCase(criteria.entityType(), targetEntityType)) {
            return false;
        }
        Map<String, String> attributes = target.attributes();
        if (StringUtils.isNotBlank(criteria.diameter())) {
            String diameter = normalizeComparableNumber(extractValue(attributes, "diameter"));
            if (!StringUtils.equals(criteria.diameter(), diameter)) {
                return false;
            }
        }
        if (StringUtils.isNotBlank(criteria.length())) {
            String length = normalizeComparableNumber(extractValue(attributes, "length"));
            if (!StringUtils.equals(criteria.length(), length)) {
                return false;
            }
        }
        if (StringUtils.isNotBlank(criteria.material())) {
            String material = StringUtils.defaultString(extractValue(attributes, "material"));
            if (!StringUtils.containsIgnoreCase(material, criteria.material())) {
                return false;
            }
        }

        if (StringUtils.isNotBlank(criteria.networkName())) {
            String networkName = StringUtils.defaultString(
                    StringUtils.defaultIfBlank(target.networkName(), extractValue(attributes, "networkName", "network_name")));
            if (!StringUtils.containsIgnoreCase(networkName, criteria.networkName())) {
                return false;
            }
        }
        if (StringUtils.isNotBlank(criteria.displayName())) {
            String displayName = StringUtils.defaultString(target.displayName());
            if (!StringUtils.containsIgnoreCase(displayName, criteria.displayName())
                    && !StringUtils.containsIgnoreCase(StringUtils.defaultString(normalizedQuery), criteria.displayName())) {
                return false;
            }
        }
        return true;
    }

    private int scoreTarget(ReferenceTarget target, SemanticReferenceCriteria criteria, String normalizedQuery) {
        if (target == null) {
            return -1;
        }
        String targetEntityType = StringUtils.defaultIfBlank(target.entityType(), criteria.entityType());
        if (StringUtils.isNotBlank(criteria.entityType()) && StringUtils.isNotBlank(targetEntityType)
                && !StringUtils.equalsIgnoreCase(criteria.entityType(), targetEntityType)) {
            return -1;
        }
        Map<String, String> attributes = target.attributes();
        int score = 0;
        if (criteria.ordinalWithinFilteredCandidates() != null) {
            if (target.rowOrdinal() != criteria.ordinalWithinFilteredCandidates()) {
                score -= 20;
            } else {
                score += 120;
            }
        }
        if (StringUtils.isNotBlank(criteria.diameter())) {
            String diameter = normalizeComparableNumber(extractValue(attributes, "diameter"));
            if (!StringUtils.equals(criteria.diameter(), diameter)) {
                return -1;
            }
            score += 180;
        }
        if (StringUtils.isNotBlank(criteria.length())) {
            String length = normalizeComparableNumber(extractValue(attributes, "length"));
            if (!StringUtils.equals(criteria.length(), length)) {
                return -1;
            }
            score += 90;
        }
        if (StringUtils.isNotBlank(criteria.material())) {
            String material = StringUtils.defaultString(extractValue(attributes, "material"));
            if (!StringUtils.containsIgnoreCase(material, criteria.material())) {
                return -1;
            }
            score += 140;
        }
        if (StringUtils.isNotBlank(criteria.networkName())) {
            String networkName = StringUtils.defaultString(
                    StringUtils.defaultIfBlank(target.networkName(), extractValue(attributes, "networkName", "network_name")));
            if (!StringUtils.containsIgnoreCase(networkName, criteria.networkName())) {
                return -1;
            }
        }

        if (StringUtils.isNotBlank(criteria.displayName())) {
            String displayName = StringUtils.defaultString(target.displayName());
            if (!StringUtils.containsIgnoreCase(displayName, criteria.displayName())) {
                return -1;
            }
            score += 70;
        }
        if (score == 0 && StringUtils.isNotBlank(normalizedQuery) && StringUtils.isNotBlank(target.displayName())
                && StringUtils.containsIgnoreCase(normalizedQuery, target.displayName())) {
            score += 60;
        }
        return score;
    }

    private SemanticReferenceCriteria buildSemanticReferenceCriteria(String normalizedQuery, Integer ordinal,
                                                                     String entityType, List<ReferenceTarget> referenceTargets) {
        String diameter = extractComparableNumber(normalizedQuery, DIAMETER_PATTERN);
        String material = extractMatchingAttributeValue(normalizedQuery, referenceTargets, "material");
        String length = extractComparableNumber(normalizedQuery, LENGTH_PATTERN);
        String networkName = extractMatchingNetworkName(normalizedQuery, referenceTargets);
        String displayName = extractMatchingDisplayName(normalizedQuery, referenceTargets);
        return new SemanticReferenceCriteria(ordinal, entityType, diameter, material, length, networkName, displayName);
    }

    private String extractComparableNumber(String query, Pattern pattern) {
        String token = extract(query, pattern);
        return normalizeComparableNumber(token);
    }

    private String normalizeComparableNumber(String value) {
        String normalized = StringUtils.trimToEmpty(value);
        if (StringUtils.isBlank(normalized)) {
            return "";
        }
        if (normalized.endsWith(".0")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    private String extractMatchingAttributeValue(String query, List<ReferenceTarget> referenceTargets, String... keys) {
        if (StringUtils.isBlank(query) || referenceTargets == null || referenceTargets.isEmpty()) {
            return "";
        }
        String normalizedQuery = StringUtils.defaultString(query).toLowerCase(Locale.ROOT);
        for (ReferenceTarget target : referenceTargets) {
            String value = extractValue(target == null ? null : target.attributes(), keys);
            if (StringUtils.isNotBlank(value)
                    && normalizedQuery.contains(StringUtils.defaultString(value).toLowerCase(Locale.ROOT))) {
                return value;
            }
        }
        return "";
    }

    private String extractMatchingNetworkName(String query, List<ReferenceTarget> referenceTargets) {
        if (StringUtils.isBlank(query) || referenceTargets == null || referenceTargets.isEmpty()) {
            return "";
        }
        for (ReferenceTarget target : referenceTargets) {
            if (target == null || StringUtils.isBlank(target.networkName())) {
                continue;
            }
            if (StringUtils.containsIgnoreCase(query, target.networkName())) {
                return target.networkName();
            }
        }
        return "";
    }

    private String extractMatchingDisplayName(String query, List<ReferenceTarget> referenceTargets) {
        if (StringUtils.isBlank(query) || referenceTargets == null || referenceTargets.isEmpty()) {
            return "";
        }
        for (ReferenceTarget target : referenceTargets) {
            if (target == null || StringUtils.isBlank(target.displayName())) {
                continue;
            }
            if (StringUtils.containsIgnoreCase(query, target.displayName())) {
                return target.displayName();
            }
        }
        return "";
    }

    private String normalizeLayerId(String layerId, String entityType) {
        if ("pipe".equalsIgnoreCase(StringUtils.defaultString(entityType))) {
            return String.valueOf(PIPE_LAYER_ID);
        }
        return StringUtils.trimToEmpty(layerId);
    }

    private String extract(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(StringUtils.defaultString(content));
        return matcher.find() ? StringUtils.trimToEmpty(matcher.group(1)) : "";
    }

    private String emptyToNull(String text) {
        return StringUtils.isBlank(text) ? null : text;
    }

    private BurstAnalysisResponseDTO buildSuccessResponse(PipeAnalysisRequest request, String requestUri,
                                                          String responseBody, String routeReason, String threadId, String sessionId) {
        String summary = extractSummary(responseBody);
        List<String> highlights = extractHighlights(responseBody, routeReason);
        JsonNode payload = resolvePayload(responseBody);
        ParsedBurstResult parsedResult = parseBurstResult(payload);
        ResultBO structuredResult = buildStructuredResult(request, payload, parsedResult, summary);
        saveContext(threadId, request, responseBody);
        saveStructuredContext(threadId, sessionId, structuredResult);
        return BurstAnalysisResponseDTO.builder()
                .success(true)
                .summary(summary)
                .analysisId(parsedResult.analysisId())
                .analysisType(parsedResult.analysisType())
                .networkName(parsedResult.networkName())
                .layerId(request.layerId())
                .gid(request.gid())
                .valvePlanSummary(parsedResult.valvePlanSummary())
                .mustCloseCount(parsedResult.mustCloseCount())
                .totalValveCount(parsedResult.totalValveCount())
                .affectedAreaDesc(parsedResult.affectedAreaDesc())
                .pipesCount(parsedResult.pipesCount())
                .affectedUserCount(parsedResult.affectedUserCount())
                .pipesSummary(parsedResult.pipesSummary())
                .mustCloseValves(parsedResult.mustCloseValves())
                .downstreamValveIds(parsedResult.downstreamValveIds())
                .closeValves(StringUtils.defaultString(request.closeValves()))
                .parentAnalysisId(StringUtils.defaultString(request.parentAnalysisId()))
                .requestUri(requestUri)
                .rawResponse(truncateRawResponse(responseBody))
                .highlights(highlights)
                .structuredResult(structuredResult)
                .build();
    }

    private List<String> extractHighlights(String responseBody, String routeReason) {
        List<String> highlights = new ArrayList<>();
        try {
            JsonNode payload = resolvePayloadRoot(JsonUtil.getObjectMapper().readTree(responseBody));
            appendIfPresent(highlights, payload, "summary");
            appendIfPresent(highlights, payload, "analysis_id");
            appendIfPresent(highlights, payload.path("network"), "name");
            appendIfPresent(highlights, payload, "impact_area");
            appendIfPresent(highlights, payload, "pipes_summary");
        } catch (Exception ex) {
            log.debug("Failed to extract burst-analysis highlights, routeReason={}", routeReason, ex);
        }
        return highlights;
    }

    private String extractSummary(String responseBody) {
        try {
            JsonNode payload = resolvePayloadRoot(JsonUtil.getObjectMapper().readTree(responseBody));
            String summary = firstText(payload, "summary");
            if (StringUtils.isNotBlank(summary)) {
                return summary;
            }
            String valvePlan = firstText(payload, "valve_plan");
            String impactArea = firstText(payload, "impact_area");
            String pipesSummary = firstText(payload, "pipes_summary");
            if (StringUtils.isNotBlank(valvePlan) || StringUtils.isNotBlank(impactArea)
                    || StringUtils.isNotBlank(pipesSummary)) {
                return String.join("\uFF0C",
                        List.of(valvePlan, impactArea, pipesSummary).stream().filter(StringUtils::isNotBlank).toList());
            }
            String message = firstText(payload, "message");
            if (StringUtils.isNotBlank(message)) {
                return message;
            }
            String msg = firstText(payload, "msg");
            if (StringUtils.isNotBlank(msg)) {
                return msg;
            }
        } catch (Exception ex) {
            log.debug("Failed to extract burst-analysis summary from response", ex);
        }
        return "Burst-analysis API call succeeded.";
    }

    private void appendIfPresent(List<String> highlights, JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || !node.has(fieldName)) {
            return;
        }
        JsonNode value = node.get(fieldName);
        if (value != null && !value.isNull() && !value.asText().isBlank()) {
            highlights.add(fieldName + ": " + value.asText());
        }
    }

    private ParsedBurstResult parseBurstResult(String responseBody) {
        return parseBurstResult(resolvePayload(responseBody));
    }

    private ParsedBurstResult parseBurstResult(JsonNode payload) {
        try {
            List<String> mustCloseValves = new ArrayList<>();
            JsonNode mustCloseArray = payload.path("must_close_valves");
            if (mustCloseArray.isArray()) {
                mustCloseArray.forEach(node -> {
                    String value = node.asText("");
                    if (StringUtils.isNotBlank(value)) {
                        mustCloseValves.add(value);
                    }
                });
            }

            List<String> downstreamValveIds = new ArrayList<>();
            JsonNode downstreamArray = payload.path("downstream_valves");
            if (downstreamArray.isArray()) {
                downstreamArray.forEach(node -> downstreamValveIds.add(node.asText("")));
            }

            BurstCountFieldMatch mustCloseCount = resolveIntegerField(payload, "must_close_count");
            BurstCountFieldMatch totalValveCount = resolveIntegerField(payload, "total_valves_affected");
            BurstCountFieldMatch pipesCount = resolveIntegerField(payload, "pipes_affected");
            BurstCountFieldMatch affectedUserCount = resolveIntegerField(payload,
                    AFFECTED_USER_COUNT_FIELDS.toArray(new String[0]));
            log.info(
                    "[CTX_TRACE][BURST_RESULT][PARSE] analysisId={} mustCloseCount={}({}) pipesCount={}({}) affectedUserCount={}({}) totalValveCount={}({})",
                    firstText(payload, "analysis_id"), mustCloseCount.value(), mustCloseCount.fieldName(), pipesCount.value(),
                    pipesCount.fieldName(), affectedUserCount.value(), affectedUserCount.fieldName(), totalValveCount.value(),
                    totalValveCount.fieldName());

            return new ParsedBurstResult(firstText(payload, "analysis_id"),
                    translateAnalysisType(firstText(payload, "analysis_type")),
                    firstText(payload.path("network"), "name"),
                    firstText(payload, "valve_plan"),
                    mustCloseCount.value(),
                    totalValveCount.value(),
                    firstText(payload, "impact_area"),
                    pipesCount.value(),
                    affectedUserCount.value(),
                    firstText(payload, "pipes_summary"),
                    mustCloseValves, downstreamValveIds);
        } catch (Exception ex) {
            log.debug("Failed to parse structured burst-analysis result", ex);
            return new ParsedBurstResult("", "", "", "", null, null, "", null, null, "", List.of(), List.of());
        }
    }

    private JsonNode resolvePayload(String responseBody) {
        try {
            return resolvePayloadRoot(JsonUtil.getObjectMapper().readTree(responseBody));
        } catch (Exception ex) {
            log.debug("Failed to resolve burst payload root", ex);
            return JsonUtil.getObjectMapper().createObjectNode();
        }
    }

    private BurstCountFieldMatch resolveIntegerField(JsonNode payload, String... fieldNames) {
        if (payload == null || payload.isMissingNode() || payload.isNull() || fieldNames == null) {
            return new BurstCountFieldMatch(null, "");
        }
        for (String fieldName : fieldNames) {
            Integer value = intValue(payload.path(fieldName));
            if (value != null) {
                return new BurstCountFieldMatch(value, fieldName);
            }
        }
        return new BurstCountFieldMatch(null, "");
    }

    private Integer intValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            String text = StringUtils.trimToEmpty(node.asText());
            if (text.matches("-?\\d+")) {
                return Integer.parseInt(text);
            }
        }
        return null;
    }

    private String translateAnalysisType(String analysisType) {
        return switch (StringUtils.defaultString(analysisType)) {
            case "first" -> "\u9996\u6B21\u7206\u7BA1\u5206\u6790";
            case "second" -> "\u4E8C\u6B21\u5173\u9600\u5206\u6790";
            default -> analysisType;
        };
    }

    private String prettyJson(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return "";
        }
        try {
            Object json = JsonUtil.getObjectMapper().readValue(responseBody, Object.class);
            String pretty = JsonUtil.getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json);
            return truncateRawResponse(pretty);
        } catch (Exception ex) {
            return truncateRawResponse(responseBody);
        }
    }

    private String truncateRawResponse(String rawResponse) {
        if (StringUtils.isBlank(rawResponse) || rawResponse.length() <= MAX_RAW_RESPONSE_LENGTH) {
            return rawResponse;
        }
        return rawResponse.substring(0, MAX_RAW_RESPONSE_LENGTH)
                + "\n... [truncated, original response is too large to display in full]";
    }

    private String firstText(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || !node.has(fieldName)) {
            return "";
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private BurstAnalysisResponseDTO buildFailure(String summary) {
        return BurstAnalysisResponseDTO.builder().success(false).summary(summary).highlights(List.of(summary)).build();
    }

    private ResultBO buildStructuredResult(PipeAnalysisRequest request, JsonNode payload, ParsedBurstResult parsedResult,
                                           String summary) {
        List<ResultSectionBO> sections = new ArrayList<>();
        sections.add(buildOverviewSection(request, parsedResult, summary));

        ResultSectionBO mustCloseSection = buildValveSection("must_close_valves", "Must Close Valves", "must_close",
                mergeValveRows(payload.path("valve_details").path("must_close"), payload.path("must_close_valves"),
                        "must_close"),
                parsedResult.networkName());
        if (mustCloseSection != null) {
            sections.add(mustCloseSection);
        }

        ResultSectionBO affectedPipeSection = buildAffectedPipeSection(request, payload, parsedResult);
        if (affectedPipeSection != null) {
            sections.add(affectedPipeSection);
        }

        ResultSectionBO downstreamValveSection = buildValveSection("downstream_valves", "Downstream Valves", "downstream",
                mergeValveRows(payload.path("valve_details").path("downstream"), payload.path("downstream_valves"),
                        "downstream"),
                parsedResult.networkName());
        if (downstreamValveSection != null) {
            sections.add(downstreamValveSection);
        }

        ResultSectionBO failedValveSection = buildValveSection("failed_valves", "Failed Valves", "failed",
                collectValveRows(payload.path("valve_details").path("failed"), "failed"), parsedResult.networkName());
        if (failedValveSection != null) {
            sections.add(failedValveSection);
        }

        String activeSectionKey = chooseActiveSectionKey(sections);
        ResultSectionBO activeSection = findSection(sections, activeSectionKey);
        return ResultBO.builder()
                .sceneType("burst_analysis")
                .summary(summary)
                .activeSectionKey(activeSectionKey)
                .sections(sections)
                .resultSet(activeSection == null ? null : activeSection.getResultSet())
                .referencePreview(activeSection == null ? null : activeSection.getReferencePreview())
                .referenceTargets(activeSection == null ? List.of() : activeSection.getReferenceTargets())
                .displayStyle(null)
                .build();
    }

    private ResultSectionBO buildOverviewSection(PipeAnalysisRequest request, ParsedBurstResult parsedResult, String summary) {
        Map<String, String> row = new LinkedHashMap<>();
        putIfNotBlank(row, "summary", summary);
        putIfNotBlank(row, "analysisId", parsedResult.analysisId());
        putIfNotBlank(row, "analysisType", parsedResult.analysisType());
        putIfNotBlank(row, "networkName", parsedResult.networkName());
        putIfNotBlank(row, "burstLayerId", request.layerId());
        putIfNotBlank(row, "burstGid", request.gid());
        putIfNotBlank(row, "valvePlanSummary", parsedResult.valvePlanSummary());
        putIfNotBlank(row, "affectedAreaDesc", parsedResult.affectedAreaDesc());
        putIfNotBlank(row, "pipesSummary", parsedResult.pipesSummary());
        putIfNotNull(row, "mustCloseCount", parsedResult.mustCloseCount());
        putIfNotNull(row, "totalValveCount", parsedResult.totalValveCount());
        putIfNotNull(row, "pipesCount", parsedResult.pipesCount());
        putIfNotNull(row, "affectedUserCount", parsedResult.affectedUserCount());
        return ResultSectionBO.builder()
                .key("analysis_overview")
                .title("Burst Analysis Overview")
                .entityType("")
                .summary(summary)
                .resultSet(buildResultSet(List.of(row)))
                .referenceTargets(List.of())
                .build();
    }

    private ResultSectionBO buildValveSection(String key, String title, String defaultType, List<Map<String, String>> rows,
                                              String networkName) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<ReferenceTargetBO> targets = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            String gid = firstNonBlank(row, "id", "gid", "valveId", "deviceId");
            if (StringUtils.isBlank(gid)) {
                continue;
            }
            targets.add(ReferenceTargetBO.builder()
                    .entityType("valve")
                    .rowOrdinal(i + 1)
                    .gid(gid)
                    .layerId(firstNonBlank(row, "layerId", "layer_id"))
                    .displayName(firstNonBlank(row, "deviceName", "name", "displayName"))
                    .networkName(networkName)
                    .attributes(new LinkedHashMap<>(row))
                    .supplementalReference(false)
                    .build());
        }
        String summary = rows.size() + " " + title + " rows";
        return ResultSectionBO.builder()
                .key(key)
                .title(title)
                .entityType("valve")
                .summary(summary)
                .resultSet(buildResultSet(rows))
                .referencePreview(targets.isEmpty() ? null : toReferencePreview(targets.get(0)))
                .referenceTargets(targets)
                .build();
    }

    private ResultSectionBO buildAffectedPipeSection(PipeAnalysisRequest request, JsonNode payload,
                                                     ParsedBurstResult parsedResult) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> pipeGids = new ArrayList<>();
        JsonNode pipeGidList = payload.path("pipes_gid_list");
        if (pipeGidList.isArray()) {
            pipeGidList.forEach(node -> {
                String gid = node.asText("");
                if (StringUtils.isNotBlank(gid)) {
                    pipeGids.add(gid);
                }
            });
        }
        if (pipeGids.isEmpty() && StringUtils.isNotBlank(request.gid())) {
            pipeGids.add(request.gid());
        }
        for (String gid : pipeGids) {
            Map<String, String> row = new LinkedHashMap<>();
            putIfNotBlank(row, "gid", gid);
            putIfNotBlank(row, "layerId", String.valueOf(PIPE_LAYER_ID));
            putIfNotBlank(row, "networkName", parsedResult.networkName());
            putIfNotBlank(row, "analysisId", parsedResult.analysisId());
            putIfNotBlank(row, "analysisType", parsedResult.analysisType());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return null;
        }
        List<ReferenceTargetBO> targets = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            targets.add(ReferenceTargetBO.builder()
                    .entityType("pipe")
                    .rowOrdinal(i + 1)
                    .gid(row.get("gid"))
                    .layerId(row.get("layerId"))
                    .displayName("pipe gid=" + row.get("gid"))
                    .networkName(row.get("networkName"))
                    .attributes(new LinkedHashMap<>(row))
                    .supplementalReference(false)
                    .build());
        }
        String summary = StringUtils.defaultIfBlank(parsedResult.pipesSummary(), rows.size() + " affected pipes");
        return ResultSectionBO.builder()
                .key("affected_pipes")
                .title("Affected Pipes")
                .entityType("pipe")
                .summary(summary)
                .resultSet(buildResultSet(rows))
                .referencePreview(targets.isEmpty() ? null : toReferencePreview(targets.get(0)))
                .referenceTargets(targets)
                .build();
    }

    private ResultSetBO buildResultSet(List<Map<String, String>> rows) {
        List<Map<String, String>> safeRows = rows == null ? List.of() : rows.stream()
                .filter(row -> row != null && !row.isEmpty())
                .map(e -> (Map<String, String>) new LinkedHashMap())
                .toList();
        List<String> columns = safeRows.isEmpty() ? List.of() : new ArrayList<>(safeRows.get(0).keySet());
        return ResultSetBO.builder().column(columns).data(safeRows).build();
    }

    private String chooseActiveSectionKey(List<ResultSectionBO> sections) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }
        for (String preferredKey : List.of("must_close_valves", "affected_pipes", "downstream_valves", "analysis_overview")) {
            ResultSectionBO matched = findSection(sections, preferredKey);
            if (matched != null) {
                return matched.getKey();
            }
        }
        return sections.get(0).getKey();
    }

    private ResultSectionBO findSection(List<ResultSectionBO> sections, String key) {
        if (sections == null || StringUtils.isBlank(key)) {
            return null;
        }
        for (ResultSectionBO section : sections) {
            if (section != null && StringUtils.equalsIgnoreCase(key, section.getKey())) {
                return section;
            }
        }
        return null;
    }

    private void saveStructuredContext(String threadId, String sessionId, ResultBO structuredResult) {
        if (structuredResult == null || structuredResult.getSections() == null || structuredResult.getSections().isEmpty()) {
            return;
        }
        List<SectionContext> sections = structuredResult.getSections().stream()
                .map(this::toSectionContext)
                .filter(section -> section != null
                        && ((section.rows() != null && !section.rows().isEmpty())
                        || (section.referenceTargets() != null && !section.referenceTargets().isEmpty())))
                .toList();
        if (sections.isEmpty()) {
            return;
        }
        SectionContext activeSection = resolveActiveSection(sections, structuredResult.getActiveSectionKey());
        QueryResultContext queryResultContext = new QueryResultContext(
                activeSection == null ? "" : StringUtils.defaultString(activeSection.entityType()),
                activeSection == null ? "" : StringUtils.defaultString(activeSection.key()),
                activeSection == null ? List.of() : activeSection.columns(),
                activeSection == null ? List.of() : activeSection.rows(),
                activeSection == null ? List.of() : activeSection.referenceTargets(),
                StringUtils.defaultString(structuredResult.getSceneType()), StringUtils.defaultString(structuredResult.getSummary()),
                StringUtils.defaultString(structuredResult.getActiveSectionKey()), sections);
        queryResultContextManager.save(threadId, queryResultContext);

        SessionSemanticReferenceContext sessionContext = new SessionSemanticReferenceContext(
                activeSection == null ? "" : StringUtils.defaultString(activeSection.entityType()),
                activeSection == null ? List.of() : activeSection.referenceTargets(), "burst_analysis",
                StringUtils.defaultString(structuredResult.getSummary()), StringUtils.defaultString(structuredResult.getSceneType()),
                StringUtils.defaultString(structuredResult.getActiveSectionKey()),
                sections.stream().map(this::toSectionSemanticContext).toList());
        sessionSemanticReferenceContextService.save(sessionId, sessionContext);
    }

    private SectionContext toSectionContext(ResultSectionBO section) {
        return new SectionContext(StringUtils.defaultString(section.getKey()), StringUtils.defaultString(section.getTitle()),
                StringUtils.defaultString(section.getEntityType()),
                section.getResultSet() == null ? List.of() : defaultList(section.getResultSet().getColumn()),
                section.getResultSet() == null ? List.of() : defaultRows(section.getResultSet().getData()),
                toReferenceTarget(section.getReferencePreview()),
                toReferenceTargets(section.getReferenceTargets()),
                StringUtils.defaultString(section.getSummary()));
    }

    private SectionSemanticReferenceContext toSectionSemanticContext(SectionContext section) {
        return new SectionSemanticReferenceContext(section.key(), section.title(), section.entityType(),
                section.referenceTargets(), section.referencePreview(), section.rows(), section.columns(), section.summary());
    }

    private SectionContext resolveActiveSection(List<SectionContext> sections, String activeSectionKey) {
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        if (StringUtils.isNotBlank(activeSectionKey)) {
            for (SectionContext section : sections) {
                if (StringUtils.equalsIgnoreCase(activeSectionKey, section.key())) {
                    return section;
                }
            }
        }
        return sections.get(0);
    }

    private ReferencePreviewBO toReferencePreview(ReferenceTargetBO target) {
        if (target == null) {
            return null;
        }
        return ReferencePreviewBO.builder()
                .entityType(target.getEntityType())
                .rowOrdinal(target.getRowOrdinal())
                .gid(target.getGid())
                .layerId(target.getLayerId())
                .displayName(target.getDisplayName())
                .networkName(target.getNetworkName())
                .attributes(target.getAttributes())
                .supplementalReference(target.getSupplementalReference())
                .build();
    }

    private ReferenceTarget toReferenceTarget(ReferencePreviewBO preview) {
        if (preview == null || StringUtils.isBlank(preview.getGid())) {
            return null;
        }
        return new ReferenceTarget(preview.getRowOrdinal() == null ? 1 : preview.getRowOrdinal(),
                StringUtils.defaultString(preview.getEntityType()), preview.getGid(),
                StringUtils.defaultString(preview.getLayerId()), StringUtils.defaultString(preview.getDisplayName()),
                StringUtils.defaultString(preview.getNetworkName()),
                preview.getAttributes() == null ? Map.of() : new LinkedHashMap<>(preview.getAttributes()));
    }

    private List<ReferenceTarget> toReferenceTargets(List<ReferenceTargetBO> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<ReferenceTarget> results = new ArrayList<>();
        for (ReferenceTargetBO target : targets) {
            if (target == null || StringUtils.isBlank(target.getGid())) {
                continue;
            }
            results.add(new ReferenceTarget(target.getRowOrdinal() == null ? 1 : target.getRowOrdinal(),
                    StringUtils.defaultString(target.getEntityType()), target.getGid(),
                    StringUtils.defaultString(target.getLayerId()), StringUtils.defaultString(target.getDisplayName()),
                    StringUtils.defaultString(target.getNetworkName()),
                    target.getAttributes() == null ? Map.of() : new LinkedHashMap<>(target.getAttributes())));
        }
        return results;
    }

    private List<Map<String, String>> defaultRows(List<Map<String, String>> rows) {
        return rows == null ? List.of() : rows.stream().map(e -> (Map<String, String>) new LinkedHashMap()).toList();
}

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<Map<String, String>> mergeValveRows(JsonNode detailArray, JsonNode textArray, String type) {
        List<Map<String, String>> rows = new ArrayList<>(collectValveRows(detailArray, type));
        if (rows.isEmpty()) {
            rows.addAll(collectValveRowsFromNames(textArray, type));
        }
        return rows;
    }

    private List<Map<String, String>> collectValveRows(JsonNode valveArray, String type) {
        if (valveArray == null || !valveArray.isArray()) {
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        valveArray.forEach(node -> {
            Map<String, String> row = new LinkedHashMap<>();
            putIfNotBlank(row, "id", firstText(node, "id"));
            putIfNotBlank(row, "layerId", firstText(node, "layerId"));
            putIfNotBlank(row, "deviceName", firstText(node, "deviceName"));
            putIfNotBlank(row, "type", type);
            if (!row.isEmpty()) {
                rows.add(row);
            }
        });
        return rows;
    }

    private List<Map<String, String>> collectValveRowsFromNames(JsonNode valveArray, String type) {
        if (valveArray == null || !valveArray.isArray()) {
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        valveArray.forEach(node -> {
            String text = node.asText("");
            if (StringUtils.isBlank(text)) {
                return;
            }
            String[] parts = text.split("-");
            Map<String, String> row = new LinkedHashMap<>();
            putIfNotBlank(row, "deviceName", parts.length > 0 ? parts[0] : "");
            putIfNotBlank(row, "id", parts.length > 1 ? parts[parts.length - 1] : text);
            putIfNotBlank(row, "type", type);
            rows.add(row);
        });
        return rows;
    }

    private void putIfNotBlank(Map<String, String> row, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            row.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, String> row, String key, Integer value) {
        if (value != null) {
            row.put(key, String.valueOf(value));
        }
    }

    private String firstNonBlank(Map<String, String> row, String... keys) {
        if (row == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = row.get(key);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean hasUsableResultContext(QueryResultContext context) {
        if (context == null) {
            return false;
        }
        if (context.referenceTargets() != null && !context.referenceTargets().isEmpty()) {
            return true;
        }
        return context.sections() != null && context.sections().stream().anyMatch(section -> section != null
                && section.referenceTargets() != null && !section.referenceTargets().isEmpty());
    }

    private boolean hasUsableSessionContext(SessionSemanticReferenceContext context) {
        if (context == null) {
            return false;
        }
        if (context.referenceTargets() != null && !context.referenceTargets().isEmpty()) {
            return true;
        }
        return context.sections() != null && context.sections().stream().anyMatch(section -> section != null
                && section.referenceTargets() != null && !section.referenceTargets().isEmpty());
    }

    private SectionContext selectResultSection(QueryResultContext context, String entityType) {
        return context == null ? null : context.resolveSection(entityType);
    }

    private SectionSemanticReferenceContext selectSessionSection(SessionSemanticReferenceContext context, String entityType) {
        return context == null ? null : context.resolveSection(entityType);
    }

    private List<ReferenceTarget> sectionReferenceTargets(QueryResultContext context, SectionContext section) {
        if (section != null && section.referenceTargets() != null && !section.referenceTargets().isEmpty()) {
            return section.referenceTargets();
        }
        return context == null ? List.of() : context.referenceTargets();
    }

    private List<ReferenceTarget> sectionReferenceTargets(SessionSemanticReferenceContext context,
                                                          SectionSemanticReferenceContext section) {
        if (section != null && section.referenceTargets() != null && !section.referenceTargets().isEmpty()) {
            return section.referenceTargets();
        }
        return context == null ? List.of() : context.referenceTargets();
    }

    private List<Map<String, String>> sectionRows(QueryResultContext context, SectionContext section) {
        if (section != null && section.rows() != null && !section.rows().isEmpty()) {
            return section.rows();
        }
        return context == null ? List.of() : context.rows();
    }

    private void saveContext(String threadId, PipeAnalysisRequest request, String responseBody) {
        if (StringUtils.isBlank(threadId) || StringUtils.isBlank(responseBody)) {
            return;
        }
        try {
            JsonNode payload = resolvePayloadRoot(JsonUtil.getObjectMapper().readTree(responseBody));
            String analysisId = firstText(payload, "analysis_id");
            String networkName = firstText(payload.path("network"), "name");
            List<String> pipeGids = new ArrayList<>();
            JsonNode pipeGidList = payload.path("pipes_gid_list");
            if (pipeGidList.isArray()) {
                pipeGidList.forEach(node -> pipeGids.add(node.asText("")));
            }
            List<ValveRef> valves = new ArrayList<>();
            collectValves(valves, payload.path("valve_details").path("must_close"), "must_close");
            collectValveNames(valves, payload.path("must_close_valves"), "must_close");
            collectValves(valves, payload.path("valve_details").path("failed"), "failed");
            collectValves(valves, payload.path("valve_details").path("downstream"), "downstream");
            burstAnalysisContextManager.save(threadId,
                    new BurstAnalysisContext(request.layerId(), request.gid(), analysisId, pipeGids, valves, networkName));
        } catch (Exception ex) {
            log.debug("Failed to save burst-analysis context for threadId={}", threadId, ex);
        }
    }

    private JsonNode resolvePayloadRoot(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return JsonUtil.getObjectMapper().createObjectNode();
        }
        if (root.has("fullSummary") || root.has("summary")) {
            return root;
        }
        JsonNode dataNode = root.path("data");
        if (!dataNode.isMissingNode() && !dataNode.isNull()
                && (dataNode.has("fullSummary") || dataNode.has("summary"))) {
            return dataNode;
        }
        JsonNode resultNode = root.path("result");
        if (!resultNode.isMissingNode() && !resultNode.isNull()
                && (resultNode.has("fullSummary") || resultNode.has("summary"))) {
            return resultNode;
        }
        JsonNode llmContextNode = root.path("llmContext");
        if (!llmContextNode.isMissingNode() && !llmContextNode.isNull()) {
            return llmContextNode;
        }
        return root;
    }

    private void collectValves(List<ValveRef> valves, JsonNode valveArray, String type) {
        if (valveArray == null || !valveArray.isArray()) {
            return;
        }
        valveArray.forEach(node -> appendValveRef(valves, new ValveRef(firstText(node, "id"),
                firstText(node, "layerId"), firstText(node, "deviceName"), type)));
    }

    private void collectValveNames(List<ValveRef> valves, JsonNode valveArray, String type) {
        if (valveArray == null || !valveArray.isArray()) {
            return;
        }
        valveArray.forEach(node -> {
            String text = node.asText("");
            if (StringUtils.isBlank(text)) {
                return;
            }
            String[] parts = text.split("-");
            String name = parts.length > 0 ? parts[0] : "";
            String id = parts.length > 1 ? parts[parts.length - 1] : text;
            appendValveRef(valves, new ValveRef(id, "", name, type));
        });
    }

    private void appendValveRef(List<ValveRef> valves, ValveRef candidate) {
        if (candidate == null || (StringUtils.isBlank(candidate.id()) && StringUtils.isBlank(candidate.deviceName()))) {
            return;
        }
        boolean exists = valves.stream().anyMatch(existing -> sameValve(existing, candidate));
        if (!exists) {
            valves.add(candidate);
        }
    }

    private boolean sameValve(ValveRef left, ValveRef right) {
        if (left == null || right == null) {
            return false;
        }
        if (StringUtils.isNotBlank(left.id()) && StringUtils.isNotBlank(right.id())) {
            return StringUtils.equalsIgnoreCase(left.id(), right.id());
        }
        return StringUtils.isNotBlank(left.deviceName()) && StringUtils.isNotBlank(right.deviceName())
                && StringUtils.equalsIgnoreCase(left.deviceName(), right.deviceName());
    }

    private Integer parseOrdinal(String text) {
        Matcher matcher = ORDINAL_PATTERN.matcher(StringUtils.defaultString(text));
        if (!matcher.find()) {
            return null;
        }
        String token = StringUtils.deleteWhitespace(matcher.group(1));
        if (token.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(token);
        }
        return switch (token) {
            case "\u4E00" -> 1;
            case "\u4E8C" -> 2;
            case "\u4E09" -> 3;
            case "\u56DB" -> 4;
            case "\u4E94" -> 5;
            case "\u516D" -> 6;
            case "\u4E03" -> 7;
            case "\u516B" -> 8;
            case "\u4E5D" -> 9;
            case "\u5341" -> 10;
            default -> null;
        };
    }

    private String resolvePipeGid(BurstAnalysisContext context, Integer ordinal) {
        List<String> pipeGids = context.pipeGids();
        if (pipeGids == null || pipeGids.isEmpty()) {
            return "";
        }
        if (ordinal == null || ordinal <= 0 || ordinal > pipeGids.size()) {
            return pipeGids.get(0);
        }
        return pipeGids.get(ordinal - 1);
    }

    private String resolveValveId(BurstAnalysisContext context, Integer ordinal) {
        List<ValveRef> valves = context.valves();
        if (valves == null || valves.isEmpty()) {
            return "";
        }
        List<ValveRef> actionableValves = valves.stream().filter(valve -> StringUtils.isNotBlank(valve.id())).toList();
        if (!actionableValves.isEmpty()) {
            if (ordinal == null || ordinal <= 0 || ordinal > actionableValves.size()) {
                return actionableValves.get(0).id();
            }
            return actionableValves.get(ordinal - 1).id();
        }
        if (ordinal == null || ordinal <= 0 || ordinal > valves.size()) {
            return valves.get(0).id();
        }
        return valves.get(ordinal - 1).id();
    }

    private boolean looksLikeLatestResultReference(String normalizedQuery, Integer ordinal, String entityType) {
        boolean hasReferenceKeywords = containsAny(normalizedQuery, QUERY_RESULT_REFERENCE_KEYWORDS);
        boolean hasPipeAttributeConstraint = containsAny(normalizedQuery, PIPE_ATTRIBUTE_KEYWORDS);
        if (ordinal == null && !hasReferenceKeywords && !hasPipeAttributeConstraint) {
            return false;
        }
        if ("pipe".equalsIgnoreCase(StringUtils.defaultString(entityType))) {
            return containsAny(normalizedQuery, PIPE_REFERENCE_KEYWORDS)
                    || hasReferenceKeywords
                    || hasPipeAttributeConstraint;
        }
        if ("valve".equalsIgnoreCase(StringUtils.defaultString(entityType))) {
            return containsAny(normalizedQuery, VALVE_REFERENCE_KEYWORDS)
                    || hasReferenceKeywords;
        }
        return hasReferenceKeywords;
    }

    private Map<String, String> resolveTargetRow(List<Map<String, String>> rows, Integer ordinal) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (ordinal == null || ordinal <= 0) {
            return rows.get(0);
        }
        if (ordinal > rows.size()) {
            return null;
        }
        return rows.get(ordinal - 1);
    }

    private String inferEntityType(QueryResultContext context, String currentEntityType, String normalizedQuery) {
        if (StringUtils.isNotBlank(currentEntityType)) {
            return currentEntityType;
        }
        String tableName = StringUtils.defaultString(context.tableName()).toLowerCase();
        if (tableName.contains("pipe") || tableName.contains("line") || tableName.contains("\u7BA1")) {
            return "pipe";
        }
        if (tableName.contains("valve") || tableName.contains("\u9600")) {
            return "valve";
        }

        List<String> columns = context.columns();
        if (columns != null) {
            for (String column : columns) {
                String normalizedColumn = StringUtils.defaultString(column).toLowerCase();
                if (normalizedColumn.contains("pipe") || normalizedColumn.contains("diameter")
                        || normalizedColumn.contains("\u7BA1\u5F84") || normalizedColumn.contains("\u7BA1\u7EBF")) {
                    return "pipe";
                }
                if (normalizedColumn.contains("valve") || normalizedColumn.contains("\u9600\u95E8")) {
                    return "valve";
                }
            }
        }

        if (containsAny(normalizedQuery, PIPE_REFERENCE_KEYWORDS) || normalizedQuery.contains("\u7206\u7BA1")) {
            return "pipe";
        }
        if (containsAny(normalizedQuery, VALVE_REFERENCE_KEYWORDS)) {
            return "valve";
        }
        return "";
    }

    private String inferEntityType(String currentEntityType, String normalizedQuery) {
        if (StringUtils.isNotBlank(currentEntityType)) {
            return currentEntityType;
        }
        if (containsAny(normalizedQuery, PIPE_REFERENCE_KEYWORDS) || normalizedQuery.contains("\u7206\u7BA1")) {
            return "pipe";
        }
        if (containsAny(normalizedQuery, VALVE_REFERENCE_KEYWORDS)) {
            return "valve";
        }
        return "";
    }

    private String extractValue(Map<String, String> row, String... candidateKeys) {
        if (row == null || row.isEmpty() || candidateKeys == null) {
            return "";
        }
        for (String candidateKey : candidateKeys) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(candidateKey)
                        && StringUtils.isNotBlank(entry.getValue())) {
                    return entry.getValue().trim();
                }
            }
        }
        return "";
    }

    private String summarizeQueryResultContext(QueryResultContext context) {
        if (context == null) {
            return "context=null";
        }
        List<Map<String, String>> rows = context.rows();
        List<Map<String, String>> sampleRows = rows == null ? List.of() : rows.stream().limit(3).toList();
        List<ReferenceTarget> referenceTargets = context.referenceTargets();
        List<ReferenceTarget> sampleTargets = referenceTargets == null ? List.of()
                : referenceTargets.stream().limit(3).toList();
        return "entityType=" + StringUtils.defaultString(context.entityType()) + ", tableName="
                + StringUtils.defaultString(context.tableName()) + ", columns=" + context.columns() + ", rowCount="
                + (rows == null ? 0 : rows.size()) + ", sampleRows="
                + StringUtils.abbreviate(String.valueOf(sampleRows), 1200) + ", referenceTargetCount="
                + (referenceTargets == null ? 0 : referenceTargets.size()) + ", sampleReferenceTargets="
                + StringUtils.abbreviate(String.valueOf(sampleTargets), 1200);
    }

    private String summarizeSessionContext(SessionSemanticReferenceContext context) {
        if (context == null) {
            return "context=null";
        }
        List<ReferenceTarget> referenceTargets = context.referenceTargets();
        List<ReferenceTarget> sampleTargets = referenceTargets == null ? List.of()
                : referenceTargets.stream().limit(3).toList();
        return "entityType=" + StringUtils.defaultString(context.entityType()) + ", source="
                + StringUtils.defaultString(context.source()) + ", querySummary="
                + StringUtils.abbreviate(StringUtils.defaultString(context.querySummary()), 300)
                + ", referenceTargetCount=" + (referenceTargets == null ? 0 : referenceTargets.size())
                + ", sampleReferenceTargets=" + StringUtils.abbreviate(String.valueOf(sampleTargets), 1200);
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private boolean isValveActionRequest(String normalizedQuery) {
        if (StringUtils.isBlank(normalizedQuery) || containsAny(normalizedQuery, VALVE_INFO_QUERY_KEYWORDS)) {
            return false;
        }
        return containsAny(normalizedQuery, REANALYZE_KEYWORDS) || containsAny(normalizedQuery, CLOSE_ACTION_KEYWORDS);
    }

    private String mergeCloseValves(String existing, String valveId) {
        if (StringUtils.isBlank(existing)) {
            return valveId;
        }
        if (existing.contains(valveId)) {
            return existing;
        }
        return existing + "," + valveId;
    }

private record PipeAnalysisRequest(String layerId, String gid, String closeValves, String parentAnalysisId) {
}

private record SemanticReferenceCriteria(Integer ordinalWithinFilteredCandidates, String entityType, String diameter,
                                         String material, String length, String networkName, String displayName) {

    private boolean hasStrongAttributeFilters() {
        return StringUtils.isNotBlank(diameter) || StringUtils.isNotBlank(material)
                || StringUtils.isNotBlank(length) || StringUtils.isNotBlank(networkName)
                || StringUtils.isNotBlank(displayName);
    }
}

private record ScoredReferenceTarget(ReferenceTarget target, int score) {
}

private record BurstCountFieldMatch(Integer value, String fieldName) {
}

private record ParsedBurstResult(String analysisId, String analysisType, String networkName,
                                 String valvePlanSummary, Integer mustCloseCount, Integer totalValveCount,
                                 String affectedAreaDesc,
                                 Integer pipesCount, Integer affectedUserCount, String pipesSummary,
                                 List<String> mustCloseValves,
                                 List<String> downstreamValveIds) {
}

}
