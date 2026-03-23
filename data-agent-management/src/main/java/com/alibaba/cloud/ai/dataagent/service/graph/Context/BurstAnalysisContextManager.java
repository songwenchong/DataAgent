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
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class BurstAnalysisContextManager {

	private final Map<String, BurstAnalysisContext> contextByThread = new ConcurrentHashMap<>();

	public void save(String threadId, BurstAnalysisContext context) {
		if (StringUtils.isBlank(threadId) || context == null) {
			return;
		}
		contextByThread.put(threadId, context);
	}

	public BurstAnalysisContext get(String threadId) {
		if (StringUtils.isBlank(threadId)) {
			return null;
		}
		return contextByThread.get(threadId);
	}

	public record BurstAnalysisContext(String sourceLayerId, String sourceGid, String analysisId,
			List<String> pipeGids, List<ValveRef> valves, String networkName) {
	}

	public record ValveRef(String id, String layerId, String deviceName, String type) {
	}

}
