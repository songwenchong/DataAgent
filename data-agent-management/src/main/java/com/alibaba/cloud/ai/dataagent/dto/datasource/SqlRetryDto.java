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
package com.alibaba.cloud.ai.dataagent.dto.datasource;

public record SqlRetryDto(String reason, SqlRetryType type) {

	public enum SqlRetryType {

		RETRYABLE_SQL_ERROR, NON_RETRYABLE_SQL_ERROR, NO_TARGET_FOUND, EMPTY_RESULT, SEMANTIC_VALIDATION_FAIL, NONE

	}

	public static SqlRetryDto semantic(String reason) {
		return new SqlRetryDto(reason, SqlRetryType.SEMANTIC_VALIDATION_FAIL);
	}

	public static SqlRetryDto retryableSql(String reason) {
		return new SqlRetryDto(reason, SqlRetryType.RETRYABLE_SQL_ERROR);
	}

	public static SqlRetryDto nonRetryableSql(String reason) {
		return new SqlRetryDto(reason, SqlRetryType.NON_RETRYABLE_SQL_ERROR);
	}

	public static SqlRetryDto noTargetFound(String reason) {
		return new SqlRetryDto(reason, SqlRetryType.NO_TARGET_FOUND);
	}

	public static SqlRetryDto emptyResult(String reason) {
		return new SqlRetryDto(reason, SqlRetryType.EMPTY_RESULT);
	}

	public static SqlRetryDto empty() {
		return new SqlRetryDto("", SqlRetryType.NONE);
	}

	public boolean isSemanticValidationFail() {
		return type == SqlRetryType.SEMANTIC_VALIDATION_FAIL;
	}

	public boolean isRetryableSqlError() {
		return type == SqlRetryType.RETRYABLE_SQL_ERROR;
	}

	public boolean isTerminalSqlState() {
		return type == SqlRetryType.NON_RETRYABLE_SQL_ERROR || type == SqlRetryType.NO_TARGET_FOUND
				|| type == SqlRetryType.EMPTY_RESULT;
	}

}
