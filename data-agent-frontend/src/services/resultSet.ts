/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
export interface ResultData {
  sceneType?: string;
  summary?: string;
  activeSectionKey?: string;
  sections?: ResultSectionData[];
  displayStyle?: ResultDisplayStyleBO;
  resultSet: ResultSetData;
  referencePreview?: ReferencePreviewData;
  referenceTargets?: ReferencePreviewData[];
}

export interface ResultSectionData {
  key?: string;
  title?: string;
  entityType?: string;
  summary?: string;
  resultSet: ResultSetData;
  referencePreview?: ReferencePreviewData;
  referenceTargets?: ReferencePreviewData[];
}

export interface ResultDisplayStyleBO {
  type: string;
  title: string;
  x: string;
  y: Array<string>;
}
/**
 * 结果集数据结构
 */
export interface ResultSetData {
  column: string[];
  data: Array<Record<string, string>>;
  errorMsg?: string;
}

export interface ReferencePreviewData {
  entityType?: string;
  rowOrdinal?: number;
  gid?: string;
  layerId?: string;
  displayName?: string;
  networkName?: string;
  attributes?: Record<string, string>;
  supplementalReference?: boolean;
}

/**
 * 分页配置
 */
export interface PaginationConfig {
  currentPage: number;
  pageSize: number;
  total: number;
}

/**
 * 结果集显示配置
 */
export interface ResultSetDisplayConfig {
  showSqlResults: boolean;
  pageSize: number;
}
