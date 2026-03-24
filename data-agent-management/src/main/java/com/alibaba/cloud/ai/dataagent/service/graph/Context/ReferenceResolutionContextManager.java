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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class ReferenceResolutionContextManager {

	private final Map<String, ReferenceContext> latestReferenceContext = new ConcurrentHashMap<>();

	public void save(String threadId, String querySummary, String entityType) {
		if (StringUtils.isAnyBlank(threadId, querySummary, entityType)) {
			return;
		}
		latestReferenceContext.put(threadId, new ReferenceContext(querySummary.trim(), entityType.trim()));
	}

	public ReferenceContext get(String threadId) {
		if (StringUtils.isBlank(threadId)) {
			return null;
		}
		return latestReferenceContext.get(threadId);
	}

	public void clear(String threadId) {
		if (StringUtils.isBlank(threadId)) {
			return;
		}
		latestReferenceContext.remove(threadId);
	}

	public record ReferenceContext(String querySummary, String entityType) {
	}

}
