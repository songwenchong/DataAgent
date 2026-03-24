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
package com.alibaba.cloud.ai.dataagent.dto.burst;

import com.alibaba.cloud.ai.dataagent.bo.schema.ResultBO;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BurstAnalysisResponseDTO {

	private boolean success;

	private String summary;

	private String analysisId;

	private String analysisType;

	private String networkName;

	private String layerId;

	private String gid;

	private String valvePlanSummary;

	private Integer mustCloseCount;

	private Integer totalValveCount;

	private String affectedAreaDesc;

	private Integer pipesCount;

	private Integer affectedUserCount;

	private String pipesSummary;

	private List<String> mustCloseValves;

	private List<String> downstreamValveIds;

	private String closeValves;

	private String parentAnalysisId;

	private String requestUri;

	private String rawResponse;

	private List<String> highlights;

	private ResultBO structuredResult;

}
