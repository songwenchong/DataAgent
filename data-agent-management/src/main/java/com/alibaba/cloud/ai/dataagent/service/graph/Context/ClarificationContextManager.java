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
public class ClarificationContextManager {

	private final Map<String, PendingClarification> pendingClarifications = new ConcurrentHashMap<>();

	public void savePending(String threadId, String originalQuery, String missingSlot, String clarificationMessage) {
		if (StringUtils.isAnyBlank(threadId, originalQuery, missingSlot, clarificationMessage)) {
			return;
		}
		pendingClarifications.put(threadId,
				new PendingClarification(originalQuery.trim(), missingSlot.trim(), clarificationMessage.trim()));
	}

	public PendingClarification getPending(String threadId) {
		if (StringUtils.isBlank(threadId)) {
			return null;
		}
		return pendingClarifications.get(threadId);
	}

	public void clear(String threadId) {
		if (StringUtils.isBlank(threadId)) {
			return;
		}
		pendingClarifications.remove(threadId);
	}

	public record PendingClarification(String originalQuery, String missingSlot, String clarificationMessage) {
	}

}
