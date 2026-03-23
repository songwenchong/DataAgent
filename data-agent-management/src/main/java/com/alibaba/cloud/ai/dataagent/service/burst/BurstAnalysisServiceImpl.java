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

import com.alibaba.cloud.ai.dataagent.dto.burst.BurstAnalysisResponseDTO;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager.BurstAnalysisContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager.ValveRef;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.QueryResultContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ReferenceResolutionContextManager;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
			Pattern.compile("\u7B2C([\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341\u767E0-9]+)[\u6761\u4E2A]?");

	private static final List<String> PIPE_REFERENCE_KEYWORDS = List.of("\u7BA1\u7EBF", "\u7BA1\u9053",
			"\u7BA1\u6BB5", "\u8FD9\u6761", "\u8BE5\u7BA1\u7EBF", "\u4E0A\u4E00\u6761");

	private static final List<String> VALVE_REFERENCE_KEYWORDS = List.of("\u9600\u95E8", "\u5173\u9600");

	private static final List<String> REANALYZE_KEYWORDS = List.of("\u91CD\u65B0\u5206\u6790",
			"\u4E8C\u6B21\u5206\u6790", "\u4E8C\u6B21\u5173\u9600", "\u5931\u6548",
			"\u91CD\u65B0\u5173\u9600");

	private static final List<String> QUERY_RESULT_REFERENCE_KEYWORDS = List.of("\u6570\u636E", "\u7ED3\u679C",
			"\u8BB0\u5F55", "\u7B2C\u4E00\u6761", "\u7B2C\u4E00\u4E2A", "\u8FD9\u6761", "\u8FD9\u4E2A");

	private static final String BURST_POINT = "\u7206\u7BA1\u70B9";

	private final WebClient.Builder webClientBuilder;

	private final DataAgentProperties dataAgentProperties;

	private final BurstAnalysisContextManager burstAnalysisContextManager;

	private final QueryResultContextManager queryResultContextManager;

	private final ReferenceResolutionContextManager referenceResolutionContextManager;

	public BurstAnalysisServiceImpl(WebClient.Builder webClientBuilder, DataAgentProperties dataAgentProperties,
			BurstAnalysisContextManager burstAnalysisContextManager,
			QueryResultContextManager queryResultContextManager,
			ReferenceResolutionContextManager referenceResolutionContextManager) {
		this.webClientBuilder = webClientBuilder;
		this.dataAgentProperties = dataAgentProperties;
		this.burstAnalysisContextManager = burstAnalysisContextManager;
		this.queryResultContextManager = queryResultContextManager;
		this.referenceResolutionContextManager = referenceResolutionContextManager;
	}

	@Override
	public BurstAnalysisResponseDTO analyze(String query, String multiTurnContext, String routeReason,
			String agentId, String threadId) {
		DataAgentProperties.BurstAnalysis properties = dataAgentProperties.getBurstAnalysis();
		if (!properties.isEnabled()) {
			return buildFailure("Burst-analysis integration is disabled by configuration.");
		}
		if (StringUtils.isBlank(properties.getBaseUrl())) {
			return buildFailure("Burst-analysis baseUrl is not configured.");
		}

		PipeAnalysisRequest request = extractRequest(query, multiTurnContext, threadId);
		if (StringUtils.isBlank(request.layerId()) || StringUtils.isBlank(request.gid())) {
			return BurstAnalysisResponseDTO.builder()
				.success(false)
				.summary(
						"\u8BF7\u5148\u660E\u786E\u8981\u5206\u6790\u7684\u7BA1\u7EBF\u6216\u7206\u7BA1\u70B9\u3002\u4F8B\u5982\u53EF\u4EE5\u76F4\u63A5\u6307\u5B9A\u5177\u4F53\u7BA1\u7EBF\uFF0C\u6216\u5148\u57FA\u4E8E\u67E5\u8BE2\u7ED3\u679C\u9009\u4E2D\u76EE\u6807\u540E\u518D\u8FDB\u884C\u7206\u7BA1\u5206\u6790\u3002")
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
			return buildSuccessResponse(request, requestUri, responseBody, routeReason, threadId);
		}
		catch (Exception ex) {
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

	private PipeAnalysisRequest extractRequest(String query, String multiTurnContext, String threadId) {
		String combined = StringUtils.defaultString(query) + "\n" + StringUtils.defaultString(multiTurnContext);
		PipeAnalysisRequest explicitRequest = new PipeAnalysisRequest(extract(combined, LAYER_ID_PATTERN),
				extract(combined, GID_PATTERN),
				emptyToNull(extract(combined, CLOSE_VALVES_PATTERN)),
				emptyToNull(extract(combined, PARENT_ANALYSIS_ID_PATTERN)));
		if (StringUtils.isNotBlank(explicitRequest.layerId()) && StringUtils.isNotBlank(explicitRequest.gid())) {
			return explicitRequest;
		}
		return resolveRequestFromContext(query, explicitRequest, threadId);
	}

	private PipeAnalysisRequest resolveRequestFromContext(String query, PipeAnalysisRequest explicitRequest,
			String threadId) {
		String normalized = StringUtils.defaultString(query).trim();
		Integer ordinal = parseOrdinal(normalized);
		PipeAnalysisRequest queryResultRequest = resolveRequestFromLatestQueryResult(normalized, ordinal, explicitRequest,
				threadId);
		if (queryResultRequest != explicitRequest) {
			return queryResultRequest;
		}

		BurstAnalysisContext context = burstAnalysisContextManager.get(threadId);
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

		if (containsAny(normalized, VALVE_REFERENCE_KEYWORDS) && containsAny(normalized, REANALYZE_KEYWORDS)) {
			String closeValveId = resolveValveId(context, ordinal);
			if (StringUtils.isNotBlank(closeValveId) && StringUtils.isNotBlank(context.sourceLayerId())
					&& StringUtils.isNotBlank(context.sourceGid())) {
				String closeValves = mergeCloseValves(explicitRequest.closeValves(), closeValveId);
				String parentAnalysisId = StringUtils.defaultIfBlank(explicitRequest.parentAnalysisId(),
						context.analysisId());
				return new PipeAnalysisRequest(context.sourceLayerId(), context.sourceGid(), closeValves,
						parentAnalysisId);
			}
		}

		if (normalized.contains(BURST_POINT) && StringUtils.isNotBlank(context.sourceLayerId())
				&& StringUtils.isNotBlank(context.sourceGid())) {
			return new PipeAnalysisRequest(context.sourceLayerId(), context.sourceGid(), explicitRequest.closeValves(),
					StringUtils.defaultIfBlank(explicitRequest.parentAnalysisId(), context.analysisId()));
		}

		return explicitRequest;
	}

	private PipeAnalysisRequest resolveRequestFromLatestQueryResult(String normalizedQuery, Integer ordinal,
			PipeAnalysisRequest explicitRequest, String threadId) {
		QueryResultContext resultContext = queryResultContextManager.get(threadId);
		log.info("[CTX_TRACE][BURST_REF][LOAD_CONTEXT][threadId={}] ordinal={} query={} context={}", threadId, ordinal,
				normalizedQuery, summarizeQueryResultContext(resultContext));
		if (resultContext == null || resultContext.rows() == null || resultContext.rows().isEmpty()) {
			log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=no_query_result_context", threadId);
			return explicitRequest;
		}

		String entityType = resultContext.entityType();
		ReferenceResolutionContextManager.ReferenceContext referenceContext = referenceResolutionContextManager
			.get(threadId);
		if (referenceContext != null && StringUtils.isNotBlank(referenceContext.entityType())) {
			entityType = referenceContext.entityType();
		}
		entityType = inferEntityType(resultContext, entityType, normalizedQuery);
		log.info("[CTX_TRACE][BURST_REF][ENTITY_TYPE][threadId={}] inferredEntityType={} referenceContextEntityType={}",
				threadId, entityType,
				referenceContext == null ? "" : StringUtils.defaultString(referenceContext.entityType()));

		if (!looksLikeLatestResultReference(normalizedQuery, ordinal, entityType)) {
			log.info("[CTX_TRACE][BURST_REF][MISS][threadId={}] reason=query_not_matched_for_result_reference", threadId);
			return explicitRequest;
		}

		Map<String, String> targetRow = resolveTargetRow(resultContext, ordinal);
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
		if (StringUtils.isBlank(layerId) && "pipe".equalsIgnoreCase(StringUtils.defaultString(entityType))) {
			layerId = String.valueOf(PIPE_LAYER_ID);
		}
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

	private String extract(String content, Pattern pattern) {
		Matcher matcher = pattern.matcher(StringUtils.defaultString(content));
		return matcher.find() ? StringUtils.trimToEmpty(matcher.group(1)) : "";
	}

	private String emptyToNull(String text) {
		return StringUtils.isBlank(text) ? null : text;
	}

	private BurstAnalysisResponseDTO buildSuccessResponse(PipeAnalysisRequest request, String requestUri,
			String responseBody, String routeReason, String threadId) {
		String prettyResponse = prettyJson(responseBody);
		String summary = extractSummary(responseBody);
		List<String> highlights = extractHighlights(responseBody, routeReason);
		ParsedBurstResult parsedResult = parseBurstResult(responseBody);
		saveContext(threadId, request, responseBody);
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
			.pipesSummary(parsedResult.pipesSummary())
			.mustCloseValves(parsedResult.mustCloseValves())
			.downstreamValveIds(parsedResult.downstreamValveIds())
			.closeValves(StringUtils.defaultString(request.closeValves()))
			.parentAnalysisId(StringUtils.defaultString(request.parentAnalysisId()))
			.requestUri(requestUri)
			.rawResponse(prettyResponse)
			.highlights(highlights)
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
		}
		catch (Exception ex) {
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
		}
		catch (Exception ex) {
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
		try {
			JsonNode payload = resolvePayloadRoot(JsonUtil.getObjectMapper().readTree(responseBody));
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

			return new ParsedBurstResult(firstText(payload, "analysis_id"),
					translateAnalysisType(firstText(payload, "analysis_type")),
					firstText(payload.path("network"), "name"),
					firstText(payload, "valve_plan"),
					intValue(payload.path("must_close_count")),
					intValue(payload.path("total_valves_affected")),
					firstText(payload, "impact_area"),
					intValue(payload.path("pipes_affected")),
					firstText(payload, "pipes_summary"),
					mustCloseValves, downstreamValveIds);
		}
		catch (Exception ex) {
			log.debug("Failed to parse structured burst-analysis result", ex);
			return new ParsedBurstResult("", "", "", "", null, null, "", null, "", List.of(), List.of());
		}
	}

	private Integer intValue(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		return node.isNumber() ? node.asInt() : null;
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
		}
		catch (Exception ex) {
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
			collectValveNames(valves, payload.path("must_close_valves"), "must_close");
			collectValves(valves, payload.path("valve_details").path("failed"), "failed");
			collectValves(valves, payload.path("valve_details").path("downstream"), "downstream");
			burstAnalysisContextManager.save(threadId,
					new BurstAnalysisContext(request.layerId(), request.gid(), analysisId, pipeGids, valves, networkName));
		}
		catch (Exception ex) {
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
		valveArray.forEach(node -> valves.add(new ValveRef(firstText(node, "id"), firstText(node, "layerId"),
				firstText(node, "deviceName"), type)));
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
			valves.add(new ValveRef(id, "", name, type));
		});
	}

	private Integer parseOrdinal(String text) {
		Matcher matcher = ORDINAL_PATTERN.matcher(StringUtils.defaultString(text));
		if (!matcher.find()) {
			return null;
		}
		String token = matcher.group(1);
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
		if (ordinal == null || ordinal <= 0 || ordinal > valves.size()) {
			return valves.get(0).id();
		}
		return valves.get(ordinal - 1).id();
	}

	private boolean looksLikeLatestResultReference(String normalizedQuery, Integer ordinal, String entityType) {
		if (ordinal == null && !containsAny(normalizedQuery, QUERY_RESULT_REFERENCE_KEYWORDS)) {
			return false;
		}
		if ("pipe".equalsIgnoreCase(StringUtils.defaultString(entityType))) {
			return containsAny(normalizedQuery, PIPE_REFERENCE_KEYWORDS)
					|| containsAny(normalizedQuery, QUERY_RESULT_REFERENCE_KEYWORDS);
		}
		if ("valve".equalsIgnoreCase(StringUtils.defaultString(entityType))) {
			return containsAny(normalizedQuery, VALVE_REFERENCE_KEYWORDS)
					|| containsAny(normalizedQuery, QUERY_RESULT_REFERENCE_KEYWORDS);
		}
		return containsAny(normalizedQuery, QUERY_RESULT_REFERENCE_KEYWORDS);
	}

	private Map<String, String> resolveTargetRow(QueryResultContext context, Integer ordinal) {
		List<Map<String, String>> rows = context.rows();
		if (rows == null || rows.isEmpty()) {
			return null;
		}
		if (ordinal == null || ordinal <= 0 || ordinal > rows.size()) {
			return rows.get(0);
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
		return "entityType=" + StringUtils.defaultString(context.entityType()) + ", tableName="
				+ StringUtils.defaultString(context.tableName()) + ", columns=" + context.columns() + ", rowCount="
				+ (rows == null ? 0 : rows.size()) + ", sampleRows="
				+ StringUtils.abbreviate(String.valueOf(sampleRows), 2000);
	}

	private boolean containsAny(String text, List<String> keywords) {
		return keywords.stream().anyMatch(text::contains);
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

	private record ParsedBurstResult(String analysisId, String analysisType, String networkName,
			String valvePlanSummary, Integer mustCloseCount, Integer totalValveCount, String affectedAreaDesc,
			Integer pipesCount, String pipesSummary, List<String> mustCloseValves, List<String> downstreamValveIds) {
	}

}
