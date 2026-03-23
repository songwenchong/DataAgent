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
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatMessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextBuilderService {

	private static final String EMPTY_CONTEXT = "(无)";

	private final MultiTurnContextManager multiTurnContextManager;

	private final ChatMessageService chatMessageService;

	private final DataAgentProperties properties;

	public ConversationContextBuildResult buildContext(String threadId, String sessionId, String currentQuery) {
		String runtimeSummary = multiTurnContextManager.buildRuntimeSummaryContext(threadId);
		int runtimeTurnCount = multiTurnContextManager.getRuntimeTurnCount(threadId);

		List<ChatMessage> sessionMessages = loadRecentMessages(sessionId);
		String sessionHistoryContext = buildSessionHistoryContext(sessionMessages);
		int sessionMessageCount = sessionMessages.size();

		boolean hasRuntimeSummary = hasContext(runtimeSummary);
		boolean hasSessionHistory = hasContext(sessionHistoryContext);

		String source;
		String combinedContext;
		if (hasRuntimeSummary && hasSessionHistory) {
			source = "merged";
			combinedContext = "【运行时上下文摘要】\n" + runtimeSummary + "\n\n【会话历史摘录】\n" + sessionHistoryContext;
		}
		else if (hasRuntimeSummary) {
			source = "runtime_summary";
			combinedContext = runtimeSummary;
		}
		else if (hasSessionHistory) {
			source = "session_history";
			combinedContext = sessionHistoryContext;
		}
		else {
			source = "empty";
			combinedContext = EMPTY_CONTEXT;
		}

		int lengthBeforeTrim = combinedContext.length();
		int maxContextLength = Math.max(properties.getMaxplanlength() * properties.getMaxturnhistory(), 4000);
		boolean truncated = false;
		if (combinedContext.length() > maxContextLength) {
			combinedContext = StringUtils.abbreviate(combinedContext, maxContextLength);
			truncated = true;
		}

		ConversationContextBuildResult result = new ConversationContextBuildResult(combinedContext, source,
				runtimeTurnCount, sessionMessageCount, lengthBeforeTrim, combinedContext.length(), truncated);

		log.info(
				"ContextBuild threadId={} sessionId={} source={} runtimeTurns={} sessionMessages={} lengthBeforeTrim={} lengthAfterTrim={} truncated={} query={}",
				threadId, sessionId, source, runtimeTurnCount, sessionMessageCount, lengthBeforeTrim,
				result.lengthAfterTrim(), truncated, StringUtils.abbreviate(StringUtils.defaultString(currentQuery), 200));
		log.debug("ContextBuild threadId={} sessionId={} context=\n{}", threadId, sessionId, result.context());

		return result;
	}

	private List<ChatMessage> loadRecentMessages(String sessionId) {
		if (StringUtils.isBlank(sessionId)) {
			return List.of();
		}
		List<ChatMessage> allMessages = chatMessageService.findBySessionId(sessionId);
		if (allMessages == null || allMessages.isEmpty()) {
			return List.of();
		}
		int maxMessages = Math.max(4, properties.getMaxturnhistory() * 4);
		if (allMessages.size() <= maxMessages) {
			return allMessages;
		}
		return new ArrayList<>(allMessages.subList(allMessages.size() - maxMessages, allMessages.size()));
	}

	private String buildSessionHistoryContext(List<ChatMessage> sessionMessages) {
		if (sessionMessages == null || sessionMessages.isEmpty()) {
			return EMPTY_CONTEXT;
		}
		StringBuilder builder = new StringBuilder();
		for (ChatMessage message : sessionMessages) {
			if (message == null || StringUtils.isBlank(message.getContent())) {
				continue;
			}
			String normalized = normalizeMessageContent(message);
			if (StringUtils.isBlank(normalized)) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append("\n");
			}
			builder.append(getRoleLabel(message.getRole())).append(": ").append(normalized);
		}
		return builder.length() == 0 ? EMPTY_CONTEXT : builder.toString();
	}

	private String normalizeMessageContent(ChatMessage message) {
		String content = StringUtils.defaultString(message.getContent());
		boolean htmlLike = "html".equalsIgnoreCase(message.getMessageType()) || content.contains("<");
		if (htmlLike) {
			content = content.replaceAll("(?is)<script.*?>.*?</script>", " ");
			content = content.replaceAll("(?is)<style.*?>.*?</style>", " ");
			content = content.replaceAll("(?is)<[^>]+>", " ");
		}
		content = content.replace("&nbsp;", " ");
		content = content.replace("&lt;", "<");
		content = content.replace("&gt;", ">");
		content = content.replace("&amp;", "&");
		content = content.replaceAll("\\s+", " ").trim();
		if (StringUtils.isBlank(content)) {
			return "";
		}
		return StringUtils.abbreviate(content, properties.getMaxplanlength());
	}

	private String getRoleLabel(String role) {
		String normalizedRole = StringUtils.defaultString(role).toLowerCase(Locale.ROOT);
		return switch (normalizedRole) {
			case "user" -> "用户";
			case "assistant" -> "AI";
			case "system" -> "系统";
			default -> "消息";
		};
	}

	private boolean hasContext(String context) {
		return StringUtils.isNotBlank(context) && !EMPTY_CONTEXT.equals(context);
	}

	public record ConversationContextBuildResult(String context, String source, int runtimeTurnCount,
			int sessionMessageCount, int lengthBeforeTrim, int lengthAfterTrim, boolean truncated) {
	}

}
