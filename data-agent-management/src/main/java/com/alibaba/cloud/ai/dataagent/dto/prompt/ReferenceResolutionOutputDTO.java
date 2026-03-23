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
package com.alibaba.cloud.ai.dataagent.dto.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferenceResolutionOutputDTO {

	@JsonProperty("resolved_reference")
	@JsonPropertyDescription("Whether the current query has been rewritten using reference context")
	private boolean resolvedReference;

	@JsonProperty("needs_user_confirmation")
	@JsonPropertyDescription("Whether the user must clarify the referenced target before continuing")
	private boolean needsUserConfirmation;

	@JsonProperty("response_message")
	@JsonPropertyDescription("Message shown when the reference target is still unclear")
	private String responseMessage;

	@JsonProperty("resolved_query")
	@JsonPropertyDescription("Rewritten query with explicit reference context")
	private String resolvedQuery;

	@JsonProperty("reference_context")
	@JsonPropertyDescription("Lightweight summary of the reference context used for resolution")
	private String referenceContext;

	@JsonProperty("entity_type")
	@JsonPropertyDescription("Resolved entity type, such as pipe or valve")
	private String entityType;

	@JsonProperty("reference_ordinal")
	@JsonPropertyDescription("Ordinal marker extracted from the query, such as 第三条 or 第一个")
	private String referenceOrdinal;

}
