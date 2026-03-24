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
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.ReferenceResolutionOutputDTO;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.BurstAnalysisContextManager.BurstAnalysisContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ReferenceResolutionContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ReferenceResolutionContextManager.ReferenceContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService.SectionSemanticReferenceContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService.SessionSemanticReferenceContext;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REFERENCE_CONTEXT_SUMMARY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REFERENCE_ENTITY_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REFERENCE_ORDINAL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REFERENCE_RESOLVED_QUERY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REFERENCE_RESOLUTION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SESSION_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

@Slf4j
@Component
@AllArgsConstructor
public class ReferenceResolutionNode implements NodeAction {

	private static final Pattern ORDINAL_PATTERN =
			Pattern.compile("\u7B2C\\s*([\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341\u767E0-9\\s]+)\\s*[\u6761\u4E2A\u9879\u6839]"); // NOPMD

	private static final Pattern QUANTITY_PATTERN =
			Pattern.compile("(\\d+)\\s*[\u6761\u4E2A\u9879\u6839]");

	private static final List<String> PIPE_ENTITY_KEYWORDS = List.of("\u7BA1\u7EBF", "\u7BA1\u9053",
			"\u7BA1\u6BB5", "\u7BA1\u5B50");

	private static final List<String> VALVE_ENTITY_KEYWORDS = List.of("\u9600\u95E8");

	private static final List<String> WORK_ORDER_ENTITY_KEYWORDS = List.of("\u5DE5\u5355");

	private static final List<String> DEVICE_ENTITY_KEYWORDS = List.of("\u8BBE\u5907");

	private static final List<String> PRONOUN_REFERENCE_KEYWORDS = List.of("\u8FD9\u6761", "\u90A3\u4E2A",
			"\u8FD9\u4E2A", "\u4E0A\u8FF0", "\u4E0A\u9762", "\u8FD9\u4E9B", "\u5B83", "\u8FD9\u6839", "\u90A3\u6839",
			"\u90A3\u6761", "\u4E0A\u4E00\u6839", "\u521A\u624D\u90A3\u6839");

	private static final List<String> EXPLICIT_SCOPE_KEYWORDS = List.of("\u4F9B\u6C34\u7BA1\u7F51",
			"\u6392\u6C34\u7BA1\u7F51", "\u6C61\u6C34\u7BA1\u7F51", "\u96E8\u6C34\u7BA1\u7F51",
			"\u70ED\u529B\u7BA1\u7F51", "\u71C3\u6C14\u7BA1\u7F51", "\u6D88\u9632\u7BA1\u7F51",
			"\u7BA1\u5F84", "\u9644\u8FD1", "\u76F8\u4EA4", "\u5305\u542B", "\u5927\u4E8E", "\u5C0F\u4E8E",
			">", "<");

	private static final List<String> PIPE_ATTRIBUTE_KEYWORDS = List.of("\u7BA1\u5F84", "\u7BA1\u957F", "\u7BA1\u6750",
			"\u6750\u8D28");

	private static final String CLARIFY_MESSAGE =
			"\u8BF7\u5148\u660E\u786E\u8981\u67E5\u770B\u7684\u5BF9\u8C61\uFF0C\u6216\u8005\u5148\u6267\u884C\u4E00\u6B21\u76F8\u5173\u7684\u7BA1\u7EBF\u3001\u9600\u95E8\u67E5\u8BE2\u3002";

	private static final String RESOLVED_FROM_REFERENCE_PREFIX =
			"\u57FA\u4E8E\u4E0A\u4E00\u8F6E\u7ED3\u679C\uFF08";

	private static final String RESOLVED_FROM_BURST_PREFIX =
			"\u57FA\u4E8E\u4E0A\u4E00\u8F6E\u7206\u7BA1\u5206\u6790\u7ED3\u679C\uFF08";

	private static final String RESOLVED_FROM_SESSION_PREFIX =
			"\u57FA\u4E8E\u540C\u4E00\u4F1A\u8BDD\u4E0A\u4E00\u8F6E\u7ED3\u679C\uFF08";

	private static final String PREFIX_SUFFIX = "\uFF09\uFF0C";

	private final ReferenceResolutionContextManager referenceContextManager;

	private final BurstAnalysisContextManager burstAnalysisContextManager;

	private final SessionSemanticReferenceContextService sessionSemanticReferenceContextService;

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String threadId = StateUtil.getStringValue(state, TRACE_THREAD_ID, "");
		String sessionId = StateUtil.getStringValue(state, SESSION_ID, "");
		String userInput = StateUtil.getStringValue(state, INPUT_KEY, "");
		String normalized = normalize(userInput);

		IntentRecognitionOutputDTO intentOutput = StateUtil.getObjectValue(state, INTENT_RECOGNITION_NODE_OUTPUT,
				IntentRecognitionOutputDTO.class);

		ReferenceContext referenceContext = referenceContextManager.get(threadId);
		BurstAnalysisContext burstAnalysisContext = burstAnalysisContextManager.get(threadId);
		SessionSemanticReferenceContext sessionSemanticContext = sessionSemanticReferenceContextService.resolve(sessionId);

		String entityType = detectEntityType(normalized, referenceContext, intentOutput);
		String ordinal = detectOrdinal(userInput);
		boolean hasReferenceMarker = hasReferenceMarker(normalized, ordinal);
		log.info(
				"[CTX_TRACE][REFERENCE_RESOLUTION][INPUT][threadId={}][sessionId={}] userInput={} hasReferenceMarker={} ordinal={} entityType={} hasReferenceContext={} hasBurstContext={} hasSessionSemanticContext={}",
				threadId, userInput, hasReferenceMarker, StringUtils.defaultString(ordinal),
				StringUtils.defaultString(entityType), referenceContext != null, burstAnalysisContext != null,
				sessionSemanticContext != null);

		if (hasReferenceMarker && requiresPreviousContext(normalized) && referenceContext == null
				&& !canResolveFromBurstContext(normalized, burstAnalysisContext)
				&& !canResolveFromSessionContext(normalized, sessionSemanticContext)) {
			ReferenceResolutionOutputDTO output = ReferenceResolutionOutputDTO.builder()
				.resolvedReference(false)
				.needsUserConfirmation(true)
				.responseMessage(CLARIFY_MESSAGE)
				.entityType(entityType)
				.referenceOrdinal(ordinal)
				.build();
			Flux<ChatResponse> sourceFlux = Flux.just(ChatResponseUtil.createPureResponse(CLARIFY_MESSAGE));
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
					state, null, null, result -> Map.of(REFERENCE_RESOLUTION_NODE_OUTPUT, output), sourceFlux);
			return Map.of(REFERENCE_RESOLUTION_NODE_OUTPUT, generator);
		}

		String resolvedQuery = userInput;
		String referenceSummary = null;
		boolean resolvedReference = false;
		boolean preferSessionSemanticContext = prefersSessionSemanticContext(normalized, entityType);
		if (hasReferenceMarker && canResolveFromSessionContext(normalized, sessionSemanticContext)
				&& requiresPreviousContext(normalized) && preferSessionSemanticContext) {
			referenceSummary = buildSessionReferenceSummary(sessionSemanticContext, parseOrdinalIndex(userInput));
			resolvedQuery = RESOLVED_FROM_SESSION_PREFIX + referenceSummary + PREFIX_SUFFIX + userInput;
			resolvedReference = true;
			if (StringUtils.isBlank(entityType)) {
				entityType = StringUtils.defaultString(sessionSemanticContext.entityType());
			}
			log.info("Reference resolved from session semantic context for sessionId={}, summary={}", sessionId,
					referenceSummary);
		}
		else if (hasReferenceMarker && referenceContext != null && requiresPreviousContext(normalized)) {
			referenceSummary = referenceContext.querySummary();
			resolvedQuery = RESOLVED_FROM_REFERENCE_PREFIX + referenceSummary + PREFIX_SUFFIX + userInput;
			resolvedReference = true;
			log.info("Reference resolved for threadId={}, summary={}", threadId, referenceSummary);
		}
		else if (hasReferenceMarker && canResolveFromBurstContext(normalized, burstAnalysisContext)
				&& requiresPreviousContext(normalized)) {
			referenceSummary = buildBurstReferenceSummary(burstAnalysisContext);
			resolvedQuery = RESOLVED_FROM_BURST_PREFIX + referenceSummary + PREFIX_SUFFIX + userInput;
			resolvedReference = true;
			if (StringUtils.isBlank(entityType)) {
				entityType = detectBurstEntityType(normalized);
			}
			log.info("Reference resolved from burst-analysis context for threadId={}, summary={}", threadId,
					referenceSummary);
		}
		else if (hasReferenceMarker && canResolveFromSessionContext(normalized, sessionSemanticContext)
				&& requiresPreviousContext(normalized)) {
			referenceSummary = buildSessionReferenceSummary(sessionSemanticContext, parseOrdinalIndex(userInput));
			resolvedQuery = RESOLVED_FROM_SESSION_PREFIX + referenceSummary + PREFIX_SUFFIX + userInput;
			resolvedReference = true;
			if (StringUtils.isBlank(entityType)) {
				entityType = StringUtils.defaultString(sessionSemanticContext.entityType());
			}
			log.info("Reference resolved from session semantic context for sessionId={}, summary={}", sessionId,
					referenceSummary);
		}

		if (StringUtils.isNotBlank(entityType) && shouldPersistReferenceContext(normalized, hasReferenceMarker)) {
			referenceContextManager.save(threadId, resolvedQuery, entityType);
		}

		ReferenceResolutionOutputDTO output = ReferenceResolutionOutputDTO.builder()
			.resolvedReference(resolvedReference)
			.needsUserConfirmation(false)
			.resolvedQuery(resolvedQuery)
			.referenceContext(referenceSummary)
			.entityType(entityType)
			.referenceOrdinal(ordinal)
			.build();
		log.info(
				"[CTX_TRACE][REFERENCE_RESOLUTION][OUTPUT][threadId={}] resolvedReference={} entityType={} ordinal={} referenceSummary={} resolvedQuery={}",
				threadId, resolvedReference, StringUtils.defaultString(entityType), StringUtils.defaultString(ordinal),
				StringUtils.defaultString(referenceSummary), resolvedQuery);
		return Map.of(REFERENCE_RESOLUTION_NODE_OUTPUT, output, INPUT_KEY, resolvedQuery, REFERENCE_RESOLVED_QUERY,
				resolvedQuery, REFERENCE_CONTEXT_SUMMARY, StringUtils.defaultString(referenceSummary),
				REFERENCE_ENTITY_TYPE, StringUtils.defaultString(entityType), REFERENCE_ORDINAL,
				StringUtils.defaultString(ordinal));
	}

	private boolean requiresPreviousContext(String normalizedInput) {
		return !containsAny(normalizedInput, EXPLICIT_SCOPE_KEYWORDS);
	}

	private boolean shouldPersistReferenceContext(String normalizedInput, boolean hasReferenceMarker) {
		if (containsAny(normalizedInput, EXPLICIT_SCOPE_KEYWORDS)) {
			return true;
		}
		return normalizedInput.contains("\u8BE6\u7EC6\u4FE1\u606F")
				|| normalizedInput.contains("\u6570\u636E")
				|| normalizedInput.contains("\u5DE5\u5355")
				|| normalizedInput.contains("\u7206\u7BA1\u5206\u6790")
				|| !hasReferenceMarker;
	}

	private boolean hasReferenceMarker(String normalizedInput, String ordinal) {
		return StringUtils.isNotBlank(ordinal) || containsAny(normalizedInput, PRONOUN_REFERENCE_KEYWORDS);
	}

	private boolean canResolveFromBurstContext(String normalizedInput, BurstAnalysisContext burstAnalysisContext) {
		if (burstAnalysisContext == null) {
			return false;
		}
		boolean hasPipeContext = burstAnalysisContext.pipeGids() != null && !burstAnalysisContext.pipeGids().isEmpty();
		boolean hasValveContext = burstAnalysisContext.valves() != null && !burstAnalysisContext.valves().isEmpty();
		return (hasPipeContext && containsAny(normalizedInput, PIPE_ENTITY_KEYWORDS))
				|| (hasValveContext && containsAny(normalizedInput, VALVE_ENTITY_KEYWORDS))
				|| (containsAny(normalizedInput, PRONOUN_REFERENCE_KEYWORDS) && (hasPipeContext || hasValveContext));
	}

	private boolean canResolveFromSessionContext(String normalizedInput,
			SessionSemanticReferenceContext sessionSemanticContext) {
		if (sessionSemanticContext == null || !hasSessionTargets(sessionSemanticContext)) {
			return false;
		}
		return containsAny(normalizedInput, PIPE_ENTITY_KEYWORDS)
				|| containsAny(normalizedInput, VALVE_ENTITY_KEYWORDS)
				|| containsAny(normalizedInput, PRONOUN_REFERENCE_KEYWORDS)
				|| StringUtils.contains(normalizedInput, "\u7b2c\u4e00\u6839")
				|| StringUtils.contains(normalizedInput, "\u7b2c\u4e00\u4e2a")
				|| containsAny(normalizedInput, PIPE_ATTRIBUTE_KEYWORDS);
	}

	private boolean prefersSessionSemanticContext(String normalizedInput, String entityType) {
		if ("pipe".equalsIgnoreCase(StringUtils.defaultString(entityType))
				|| "valve".equalsIgnoreCase(StringUtils.defaultString(entityType))) {
			return true;
		}
		return containsAny(normalizedInput, PIPE_ENTITY_KEYWORDS)
				|| containsAny(normalizedInput, VALVE_ENTITY_KEYWORDS)
				|| normalizedInput.contains("\u7206\u7BA1")
				|| containsAny(normalizedInput, PIPE_ATTRIBUTE_KEYWORDS);
	}

	private String buildBurstReferenceSummary(BurstAnalysisContext burstAnalysisContext) {
		String network = burstAnalysisContext.networkName();
		String analysisId = burstAnalysisContext.analysisId();
		String pipeCount = burstAnalysisContext.pipeGids() == null ? "0"
				: String.valueOf(burstAnalysisContext.pipeGids().size());
		String valveCount = burstAnalysisContext.valves() == null ? "0"
				: String.valueOf(burstAnalysisContext.valves().size());
		return "analysisId=" + StringUtils.defaultString(analysisId, "") + ", network="
				+ StringUtils.defaultString(network, "") + ", pipes=" + pipeCount + ", valves=" + valveCount;
	}

	private String buildSessionReferenceSummary(SessionSemanticReferenceContext sessionSemanticContext, Integer ordinal) {
		SectionSemanticReferenceContext section = sessionSemanticContext
			.resolveSection(StringUtils.defaultString(sessionSemanticContext.entityType()));
		int targetCount = section != null && section.referenceTargets() != null ? section.referenceTargets().size()
				: (sessionSemanticContext.referenceTargets() == null ? 0 : sessionSemanticContext.referenceTargets().size());
		String entityLabel = toEntityLabel(section != null ? section.entityType() : sessionSemanticContext.entityType());
		if (targetCount <= 0) {
			return "已锁定上一轮" + entityLabel + "结果，可继续按顺序或属性追问目标";
		}
		if (ordinal != null && ordinal > 0 && ordinal <= targetCount) {
			return "已锁定上一轮" + entityLabel + "结果，共 " + targetCount + " 条，可继续围绕第 " + ordinal
					+ " 条或符合条件的目标继续追问";
		}
		return "已锁定上一轮" + entityLabel + "结果，共 " + targetCount + " 条，可继续按顺序或属性追问目标";
	}

	private boolean hasSessionTargets(SessionSemanticReferenceContext sessionSemanticContext) {
		if (sessionSemanticContext == null) {
			return false;
		}
		if (sessionSemanticContext.referenceTargets() != null && !sessionSemanticContext.referenceTargets().isEmpty()) {
			return true;
		}
		return sessionSemanticContext.sections() != null && sessionSemanticContext.sections().stream().anyMatch(
				section -> section != null && section.referenceTargets() != null && !section.referenceTargets().isEmpty());
	}

	private String toEntityLabel(String entityType) {
		return switch (StringUtils.defaultString(entityType).trim().toLowerCase()) {
			case "pipe" -> "管段";
			case "valve" -> "阀门";
			case "work_order" -> "工单";
			case "device" -> "设备";
			default -> "结果";
		};
	}

	private String detectBurstEntityType(String normalizedInput) {
		if (containsAny(normalizedInput, VALVE_ENTITY_KEYWORDS)) {
			return "valve";
		}
		if (containsAny(normalizedInput, PIPE_ENTITY_KEYWORDS) || containsAny(normalizedInput, PRONOUN_REFERENCE_KEYWORDS)) {
			return "pipe";
		}
		return "";
	}

	private String detectEntityType(String normalizedInput, ReferenceContext referenceContext,
			IntentRecognitionOutputDTO intentOutput) {
		if (intentOutput != null && intentOutput.getEntities() != null) {
			String targetEntity = (String) intentOutput.getEntities().get("target_entity");
			if (StringUtils.isNotBlank(targetEntity) && !"unknown".equalsIgnoreCase(targetEntity)) {
				return targetEntity;
			}
		}

		if (containsAny(normalizedInput, VALVE_ENTITY_KEYWORDS)) {
			return "valve";
		}
		if (containsAny(normalizedInput, PIPE_ENTITY_KEYWORDS)) {
			return "pipe";
		}
		if (containsAny(normalizedInput, WORK_ORDER_ENTITY_KEYWORDS)) {
			return "work_order";
		}
		if (containsAny(normalizedInput, DEVICE_ENTITY_KEYWORDS)) {
			return "device";
		}
		return referenceContext == null ? "" : referenceContext.entityType();
	}

	private String detectOrdinal(String userInput) {
		if (StringUtils.isBlank(userInput)) {
			return "";
		}
		// If it's a pure quantity like "2个", don't treat it as ordinal
		if (QUANTITY_PATTERN.matcher(userInput).matches()) {
			return "";
		}

		Matcher matcher = ORDINAL_PATTERN.matcher(StringUtils.defaultString(userInput));
		return matcher.find() ? matcher.group() : "";
	}

	private Integer parseOrdinalIndex(String userInput) {
		if (StringUtils.isBlank(userInput)) {
			return null;
		}
		Matcher matcher = ORDINAL_PATTERN.matcher(StringUtils.defaultString(userInput));
		if (!matcher.find()) {
			return null;
		}
		String token = StringUtils.deleteWhitespace(matcher.group(1));
		if (token != null && token.chars().allMatch(Character::isDigit)) {
			return Integer.parseInt(token);
		}
		return switch (StringUtils.defaultString(token)) {
			case "一" -> 1;
			case "二" -> 2;
			case "三" -> 3;
			case "四" -> 4;
			case "五" -> 5;
			case "六" -> 6;
			case "七" -> 7;
			case "八" -> 8;
			case "九" -> 9;
			case "十" -> 10;
			default -> null;
		};
	}

	private boolean containsAny(String text, List<String> keywords) {
		return keywords.stream().anyMatch(text::contains);
	}

	private String normalize(String text) {
		return text == null ? "" : text.trim().toLowerCase();
	}

}
