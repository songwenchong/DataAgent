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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QueryResultContextManager {

	private final Map<String, QueryResultContext> latestQueryResultContext = new ConcurrentHashMap<>();

	public void save(String threadId, QueryResultContext context) {
		if (StringUtils.isBlank(threadId) || context == null || context.rows() == null || context.rows().isEmpty()) {
			return;
		}
		latestQueryResultContext.put(threadId, context);
		log.info("[CTX_TRACE][QUERY_RESULT][SAVE][threadId={}] {}", threadId, summarizeContext(context));
	}

	public QueryResultContext get(String threadId) {
		if (StringUtils.isBlank(threadId)) {
			return null;
		}
		QueryResultContext context = latestQueryResultContext.get(threadId);
		log.info("[CTX_TRACE][QUERY_RESULT][GET][threadId={}] {}", threadId, summarizeContext(context));
		return context;
	}

	public void clear(String threadId) {
		if (StringUtils.isBlank(threadId)) {
			return;
		}
		latestQueryResultContext.remove(threadId);
		log.info("[CTX_TRACE][QUERY_RESULT][CLEAR][threadId={}]", threadId);
	}

	private String summarizeContext(QueryResultContext context) {
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

	public record QueryResultContext(String entityType, String tableName, List<String> columns,
			List<Map<String, String>> rows, List<ReferenceTarget> referenceTargets) {
	}

	public record ReferenceTarget(int rowOrdinal, String entityType, String gid, String layerId, String displayName,
			String networkName, Map<String, String> attributes) {
	}

}
