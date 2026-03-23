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
package com.alibaba.cloud.ai.dataagent.service.graph.Context;

import com.alibaba.cloud.ai.dataagent.entity.ChatMessage;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatMessageService;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.ReferenceTarget;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSemanticReferenceContextService {

	private static final String PIPE_ENTITY_TYPE = "pipe";

	private final Map<String, SessionSemanticReferenceContext> latestContextBySession = new ConcurrentHashMap<>();

	private final ChatMessageService chatMessageService;

	public void save(String sessionId, SessionSemanticReferenceContext context) {
		if (StringUtils.isBlank(sessionId) || context == null || context.referenceTargets() == null
				|| context.referenceTargets().isEmpty()) {
			return;
		}
		latestContextBySession.put(sessionId, context);
		log.info("[CTX_TRACE][SESSION_REF][SAVE][sessionId={}] {}", sessionId, summarizeContext(context));
	}

	public SessionSemanticReferenceContext resolve(String sessionId) {
		if (StringUtils.isBlank(sessionId)) {
			return null;
		}
		SessionSemanticReferenceContext cached = latestContextBySession.get(sessionId);
		if (hasTargets(cached)) {
			log.info("[CTX_TRACE][SESSION_REF][LOAD][sessionId={}] source=memory {}", sessionId,
					summarizeContext(cached));
			return cached;
		}
		SessionSemanticReferenceContext recovered = recoverFromHistory(sessionId);
		if (hasTargets(recovered)) {
			latestContextBySession.put(sessionId, recovered);
			log.info("[CTX_TRACE][SESSION_REF][RECOVER][sessionId={}] source=history {}", sessionId,
					summarizeContext(recovered));
			return recovered;
		}
		log.info("[CTX_TRACE][SESSION_REF][RECOVER][sessionId={}] source=history context=null", sessionId);
		return null;
	}

	private boolean hasTargets(SessionSemanticReferenceContext context) {
		return context != null && context.referenceTargets() != null && !context.referenceTargets().isEmpty();
	}

	private SessionSemanticReferenceContext recoverFromHistory(String sessionId) {
		List<ChatMessage> messages = chatMessageService.findBySessionId(sessionId);
		if (messages == null || messages.isEmpty()) {
			return null;
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage message = messages.get(i);
			SessionSemanticReferenceContext context = parseReferenceMetadata(message);
			if (hasTargets(context)) {
				return context;
			}
			context = parseResultSetContent(message);
			if (hasTargets(context)) {
				return context;
			}
		}
		return null;
	}

	private SessionSemanticReferenceContext parseReferenceMetadata(ChatMessage message) {
		if (message == null || StringUtils.isBlank(message.getMetadata())) {
			return null;
		}
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(message.getMetadata());
			List<ReferenceTarget> targets = parseReferenceTargets(root);
			if (targets.isEmpty()) {
				return null;
			}
			return new SessionSemanticReferenceContext(normalizeEntityType(targets.get(0).entityType()), targets,
					"history_metadata", text(root, "querySummary"));
		}
		catch (Exception ex) {
			log.debug("Failed to parse session reference metadata", ex);
			return null;
		}
	}

	private SessionSemanticReferenceContext parseResultSetContent(ChatMessage message) {
		if (message == null || !"result-set".equalsIgnoreCase(StringUtils.defaultString(message.getMessageType()))
				|| StringUtils.isBlank(message.getContent())) {
			return null;
		}
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(message.getContent());
			List<ReferenceTarget> targets = parseReferenceTargets(root);
			if (targets.isEmpty()) {
				return null;
			}
			return new SessionSemanticReferenceContext(normalizeEntityType(targets.get(0).entityType()), targets,
					"history_result_set", "");
		}
		catch (Exception ex) {
			log.debug("Failed to parse result-set content for session reference recovery", ex);
			return null;
		}
	}

	private ReferenceTarget parseReferenceTarget(JsonNode preview) {
		if (preview == null || preview.isMissingNode() || preview.isNull() || !preview.isObject()) {
			return null;
		}
		String entityType = normalizeEntityType(text(preview, "entityType"));
		String gid = text(preview, "gid");
		if (StringUtils.isBlank(gid)) {
			return null;
		}
		String layerId = normalizeLayerId(text(preview, "layerId"), entityType);
		int rowOrdinal = preview.path("rowOrdinal").asInt(1);
		Map<String, String> attributes = new HashMap<>();
		JsonNode attributeNode = preview.path("attributes");
		if (attributeNode.isObject()) {
			attributeNode.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), entry.getValue().asText("")));
		}
		return new ReferenceTarget(rowOrdinal <= 0 ? 1 : rowOrdinal, entityType, gid, layerId,
				text(preview, "displayName"), text(preview, "networkName"), attributes);
	}

	private List<ReferenceTarget> parseReferenceTargets(JsonNode root) {
		if (root == null || root.isMissingNode() || root.isNull()) {
			return List.of();
		}
		List<ReferenceTarget> targets = new java.util.ArrayList<>();
		JsonNode targetArray = root.path("referenceTargets");
		if (targetArray.isArray()) {
			targetArray.forEach(node -> {
				ReferenceTarget target = parseReferenceTarget(node);
				if (target != null) {
					targets.add(target);
				}
			});
		}
		if (!targets.isEmpty()) {
			return targets;
		}
		ReferenceTarget previewTarget = parseReferenceTarget(root.path("referencePreview"));
		return previewTarget == null ? List.of() : List.of(previewTarget);
	}

	private String normalizeEntityType(String entityType) {
		return StringUtils.isBlank(entityType) ? PIPE_ENTITY_TYPE : entityType.trim();
	}

	private String normalizeLayerId(String layerId, String entityType) {
		if (PIPE_ENTITY_TYPE.equalsIgnoreCase(StringUtils.defaultString(entityType))) {
			return "0";
		}
		return StringUtils.trimToEmpty(layerId);
	}

	private String text(JsonNode node, String fieldName) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return "";
		}
		JsonNode value = node.path(fieldName);
		return value.isMissingNode() || value.isNull() ? "" : StringUtils.trimToEmpty(value.asText(""));
	}

	private String summarizeContext(SessionSemanticReferenceContext context) {
		if (context == null) {
			return "context=null";
		}
		return "entityType=" + StringUtils.defaultString(context.entityType()) + ", source="
				+ StringUtils.defaultString(context.source()) + ", querySummary="
				+ StringUtils.abbreviate(StringUtils.defaultString(context.querySummary()), 300) + ", targets="
				+ StringUtils.abbreviate(String.valueOf(context.referenceTargets()), 1200);
	}

	public record SessionSemanticReferenceContext(String entityType, List<ReferenceTarget> referenceTargets,
			String source, String querySummary) {
	}

}
