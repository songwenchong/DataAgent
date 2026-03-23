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
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class IntentRecognitionOutputDTO {

	@JsonProperty("classification")
	@JsonPropertyDescription("Backwards-compatible coarse classification result")
	private String classification;

	@JsonProperty("intent")
	@JsonPropertyDescription("One of gis_spatial_query, knowledge_qa, hello, other")
	private String intent;

	@JsonProperty("entities")
	@JsonPropertyDescription("Structured entities extracted from the current GIS query")
	private Map<String, Object> entities;

	@JsonProperty("reply_text")
	@JsonPropertyDescription("Direct reply text for hello, knowledge QA, or other")
	private String replyText;

	@JsonProperty("raw_query")
	@JsonPropertyDescription("Original user input")
	private String rawQuery;

}
