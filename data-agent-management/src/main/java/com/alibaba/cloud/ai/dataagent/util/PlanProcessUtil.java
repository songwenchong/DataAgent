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
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.dto.planner.Plan;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;

/**
 * Utility methods for plan-driven execution nodes.
 *
 * <p>
 * The lightweight SQL path does not go through PlannerNode, so callers that only need an
 * execution description should use {@link #getCurrentExecutionStepInstruction(OverAllState)}
 * instead of assuming a plan is always present.
 */
public final class PlanProcessUtil {

	private static final BeanOutputConverter<Plan> CONVERTER = new BeanOutputConverter<>(
			new ParameterizedTypeReference<>() {
			});

	private static final String STEP_PREFIX = "step_";

	private PlanProcessUtil() {
	}

	public static ExecutionStep getCurrentExecutionStep(OverAllState state) {
		Plan plan = getPlan(state);
		int currentStep = getCurrentStepNumber(state);
		return getCurrentExecutionStep(plan, currentStep);
	}

	public static String getCurrentExecutionStepInstruction(OverAllState state) {
		if (!hasPlan(state)) {
			return resolveFallbackInstruction(state);
		}
		ExecutionStep.ToolParameters currentStepParams = getCurrentExecutionStep(state).getToolParameters();
		if (currentStepParams == null || StringUtils.isBlank(currentStepParams.getInstruction())) {
			return resolveFallbackInstruction(state);
		}
		return currentStepParams.getInstruction();
	}

	public static boolean hasPlan(OverAllState state) {
		Object plannerNodeOutput = state.value(PLANNER_NODE_OUTPUT).orElse(null);
		return plannerNodeOutput instanceof String content && StringUtils.isNotBlank(content);
	}

	public static ExecutionStep getCurrentExecutionStep(Plan plan, Integer currentStep) {
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		if (executionPlan == null || executionPlan.isEmpty()) {
			throw new IllegalStateException("Execution plan is empty");
		}

		int stepIndex = currentStep - 1;
		if (stepIndex < 0 || stepIndex >= executionPlan.size()) {
			throw new IllegalStateException("Current step index is out of range: " + stepIndex);
		}

		return executionPlan.get(stepIndex);
	}

	public static Plan getPlan(OverAllState state) {
		String plannerNodeOutput = (String) state.value(PLANNER_NODE_OUTPUT)
			.orElseThrow(() -> new IllegalStateException("Planner node output is empty"));
		Plan plan = CONVERTER.convert(plannerNodeOutput);
		if (plan == null) {
			throw new IllegalStateException("Failed to parse plan output");
		}
		return plan;
	}

	public static int getCurrentStepNumber(OverAllState state) {
		return state.value(PLAN_CURRENT_STEP, 1);
	}

	public static Map<String, String> addStepResult(Map<String, String> existingResults, Integer stepNumber,
			String result) {
		Map<String, String> updatedResults = new HashMap<>(existingResults);
		updatedResults.put(STEP_PREFIX + stepNumber, result);
		return updatedResults;
	}

	private static String resolveFallbackInstruction(OverAllState state) {
		String fallbackInstruction = StateUtil.getStringValue(state, INPUT_KEY, "");
		if (StateUtil.hasValue(state, QUERY_ENHANCE_NODE_OUTPUT)) {
			try {
				fallbackInstruction = StateUtil.getCanonicalQuery(state);
			}
			catch (Exception ignore) {
				// Fall back to raw input when canonical query is unavailable.
			}
		}
		return StringUtils.isNotBlank(fallbackInstruction) ? fallbackInstruction : "No execution instruction";
	}

}
