<!--
 * Copyright 2025 the original author or authors.
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
-->

<script setup lang="ts">
  import { computed, nextTick, ref } from 'vue';
  import {
    Grid as ICON_TABLE,
    Histogram as ICON_CHART,
    CopyDocument as ICON_COPY,
  } from '@element-plus/icons-vue';
  import { ElMessage } from 'element-plus';
  import ChartComponent from './ChartComponent.vue';
  import type { ReferencePreviewData, ResultData, ResultSectionData, ResultSetData } from '@/services/resultSet';

  const props = defineProps<{
    resultData: ResultData;
    pageSize: number;
  }>();

  const isChartView = ref(true);

  const hasStructuredSections = computed(() => {
    return !!props.resultData?.sections?.some(section => {
      const resultSet = section?.resultSet;
      return (
        !!resultSet?.errorMsg ||
        !!(resultSet?.column && resultSet.column.length > 0) ||
        !!(resultSet?.data && resultSet.data.length > 0) ||
        !!(section?.referenceTargets && section.referenceTargets.length > 0) ||
        !!section?.summary
      );
    });
  });

  const isLegacyChartMode = computed(() => {
    return (
      !hasStructuredSections.value &&
      !!props.resultData?.displayStyle?.type &&
      props.resultData.displayStyle.type !== 'table'
    );
  });

  const normalizedSections = computed<ResultSectionData[]>(() => {
    if (hasStructuredSections.value) {
      return (props.resultData.sections || []).filter(Boolean);
    }
    if (!props.resultData?.resultSet) {
      return [];
    }
    return [
      {
        key: 'default',
        title: props.resultData?.displayStyle?.title || 'Result',
        entityType: props.resultData?.referencePreview?.entityType,
        summary: props.resultData?.summary,
        resultSet: props.resultData.resultSet,
        referencePreview: props.resultData?.referencePreview,
        referenceTargets: props.resultData?.referenceTargets,
      },
    ];
  });

  const activeSectionKey = computed(() => {
    return props.resultData?.activeSectionKey || normalizedSections.value[0]?.key || '';
  });

  const isBurstAnalysis = computed(() => props.resultData?.sceneType === 'burst_analysis');

  const overallSummary = computed(() => props.resultData?.summary || '');

  const escapeHtml = (text: string): string => {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  };

  const getReferenceValue = (preview: ReferencePreviewData | undefined, ...keys: string[]): string => {
    const attributes = preview?.attributes || {};
    for (const key of keys) {
      const value = attributes[key];
      if (value) {
        return value;
      }
    }
    return '';
  };

  const getEntityLabel = (entityType?: string): string => {
    if (entityType === 'pipe') return 'Pipe';
    if (entityType === 'valve') return 'Valve';
    if (entityType === 'work_order') return 'Work Order';
    if (entityType === 'device') return 'Device';
    return entityType || 'Object';
  };

  const buildReferencePreviewSummary = (preview: ReferencePreviewData | undefined): string => {
    if (!preview) {
      return '';
    }
    const ordinal = preview.rowOrdinal || 1;
    const label = getEntityLabel(preview.entityType);
    const gid = preview.gid ? `gid=${preview.gid}` : '';
    const layerId = preview.layerId ? `layerId=${preview.layerId}` : '';
    const diameter = getReferenceValue(preview, 'diameter', '管径');
    const material = getReferenceValue(preview, 'material', '管材');
    const details = [gid, layerId, diameter ? `diameter=${diameter}` : '', material ? `material=${material}` : '']
      .filter(Boolean)
      .join(', ');
    if (preview.supplementalReference) {
      return `Locked row ${ordinal} ${label}${details ? ` (${details})` : ''}`;
    }
    return `Row ${ordinal} ${label}${details ? ` (${details})` : ''}`;
  };

  const buildReferencePreviewFields = (preview: ReferencePreviewData | undefined) => {
    if (!preview) {
      return [];
    }
    return [
      { label: 'Entity', value: getEntityLabel(preview.entityType) },
      { label: 'Row', value: preview.rowOrdinal ? String(preview.rowOrdinal) : '1' },
      { label: 'gid', value: preview.gid || '' },
      { label: 'layerId', value: preview.layerId || '' },
      { label: 'diameter', value: getReferenceValue(preview, 'diameter', '管径') },
      { label: 'material', value: getReferenceValue(preview, 'material', '管材') },
      { label: 'length', value: getReferenceValue(preview, 'length', '管长') },
      { label: 'startNode', value: getReferenceValue(preview, 'stnod', '起点编号') },
      { label: 'endNode', value: getReferenceValue(preview, 'ednod', '终点编号') },
      { label: 'name', value: preview.displayName || '' },
      { label: 'network', value: preview.networkName || '' },
    ].filter(item => item.value);
  };

  const generateTableHtml = (resultSetData: ResultSetData, pageSize: number): string => {
    const columns = resultSetData?.column || [];
    const allData = resultSetData?.data || [];
    const total = allData.length;
    const totalPages = Math.max(1, Math.ceil(total / pageSize));

    let tableHtml = `<div class="result-set-container"><div class="result-set-header"><div class="result-set-info"><span>Rows: ${total}</span><div class="result-set-pagination-controls"><span class="result-set-pagination-info">Page <span class="result-set-current-page">1</span> / ${totalPages}</span><div class="result-set-pagination-buttons"><button class="result-set-pagination-btn result-set-pagination-prev" onclick="handleResultSetPagination(this, 'prev')" disabled>Prev</button><button class="result-set-pagination-btn result-set-pagination-next" onclick="handleResultSetPagination(this, 'next')" ${totalPages > 1 ? '' : 'disabled'}>Next</button></div></div></div></div><div class="result-set-table-container">`;

    for (let page = 1; page <= totalPages; page++) {
      const startIndex = (page - 1) * pageSize;
      const endIndex = Math.min(startIndex + pageSize, total);
      const currentPageData = allData.slice(startIndex, endIndex);
      tableHtml += `<div class="result-set-page ${page === 1 ? 'result-set-page-active' : ''}" data-page="${page}"><table class="result-set-table"><thead><tr>`;
      columns.forEach(column => {
        tableHtml += `<th>${escapeHtml(column)}</th>`;
      });
      tableHtml += `</tr></thead><tbody>`;

      if (currentPageData.length === 0) {
        tableHtml += `<tr><td colspan="${Math.max(columns.length, 1)}" class="result-set-empty-cell">No data</td></tr>`;
      } else {
        currentPageData.forEach(row => {
          tableHtml += '<tr>';
          columns.forEach(column => {
            tableHtml += `<td>${escapeHtml(row[column] || '')}</td>`;
          });
          tableHtml += '</tr>';
        });
      }

      tableHtml += '</tbody></table></div>';
    }

    tableHtml += '</div></div>';
    return tableHtml;
  };

  const switchToChart = () => {
    isChartView.value = true;
  };

  const switchToTable = () => {
    isChartView.value = false;
  };

  const copyJsonData = (section?: ResultSectionData) => {
    try {
      const data = section?.resultSet?.data || props.resultData?.resultSet?.data || [];
      navigator.clipboard.writeText(JSON.stringify(data, null, 2)).then(() => {
        ElMessage.success('Copied');
      });
    } catch (error) {
      console.error('Copy failed:', error);
      ElMessage.error('Copy failed');
    }
  };

  nextTick(() => {
    isChartView.value = isLegacyChartMode.value;
  });
</script>

<template>
  <div v-if="!normalizedSections.length" class="result-set-empty">No result</div>
  <div v-else class="result-display-root">
    <div v-if="overallSummary && !isBurstAnalysis" class="result-summary-card">
      <div class="result-summary-title">Summary</div>
      <div class="result-summary-content">{{ overallSummary }}</div>
    </div>

    <div
      v-for="section in normalizedSections"
      :key="section.key || section.title"
      class="agent-response-block result-section-block"
      :class="{ 'is-active-section': section.key === activeSectionKey }"
    >
      <div class="agent-response-title result-set-header-bar">
        <div class="result-section-title-wrap">
          <div class="result-section-title">{{ section.title || 'Result' }}</div>
          <div v-if="section.summary" class="result-section-summary">{{ section.summary }}</div>
        </div>
        <div class="buttons-bar">
          <div v-if="!hasStructuredSections && isLegacyChartMode" class="chart-select-container">
            <el-tooltip effect="dark" content="Chart" placement="top">
              <el-button class="tool-btn" :class="{ 'view-active': isChartView }" text @click="switchToChart">
                <el-icon size="16"><ICON_CHART /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip effect="dark" content="Table" placement="top">
              <el-button class="tool-btn" :class="{ 'view-active': !isChartView }" text @click="switchToTable">
                <el-icon size="16"><ICON_TABLE /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
          <el-tooltip effect="dark" content="Copy JSON" placement="top">
            <el-button class="tool-btn" text @click="copyJsonData(section)">
              <el-icon size="16"><ICON_COPY /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>

      <div v-if="section.referencePreview" class="reference-preview-card">
        <div class="reference-preview-title">Reference Preview</div>
        <div class="reference-preview-summary">
          {{ buildReferencePreviewSummary(section.referencePreview) }}
        </div>
        <div class="reference-preview-grid">
          <div
            v-for="item in buildReferencePreviewFields(section.referencePreview)"
            :key="item.label"
            class="reference-preview-item"
          >
            <span class="reference-preview-label">{{ item.label }}</span>
            <span class="reference-preview-value">{{ item.value }}</span>
          </div>
        </div>
      </div>

      <div v-if="section.resultSet?.errorMsg" class="result-set-error">Error: {{ section.resultSet.errorMsg }}</div>
      <div
        v-else-if="
          !section.resultSet ||
          !section.resultSet.column ||
          section.resultSet.column.length === 0 ||
          !section.resultSet.data ||
          section.resultSet.data.length === 0
        "
        class="result-set-empty"
      >
        No table data
      </div>
      <div v-else class="result-show-area">
        <ChartComponent
          v-if="!hasStructuredSections && isLegacyChartMode && isChartView"
          :resultData="resultData"
        />
        <div v-else v-html="generateTableHtml(section.resultSet, pageSize)"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
  .result-display-root {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .result-summary-card {
    padding: 16px 18px;
    border: 1px solid #dbeafe;
    border-radius: 12px;
    background: linear-gradient(180deg, #f8fbff 0%, #eef6ff 100%);
  }

  .result-summary-title {
    font-size: 14px;
    font-weight: 600;
    color: #1f2d3d;
    margin-bottom: 8px;
  }

  .result-summary-content {
    color: #334155;
    line-height: 1.7;
  }

  .result-section-block {
    overflow: hidden;
  }

  .result-section-block.is-active-section {
    border-color: #93c5fd;
    box-shadow: 0 4px 14px rgba(59, 130, 246, 0.12);
  }

  .result-set-error {
    padding: 12px;
    background-color: #fef0f0;
    border: 1px solid #fbc4c4;
    border-radius: 4px;
    color: #f56c6c;
    margin: 8px 0;
  }

  .result-set-empty {
    padding: 12px;
    background-color: #f4f4f5;
    border: 1px solid #e9e9eb;
    border-radius: 4px;
    color: #909399;
    margin: 8px 0;
  }

  .result-set-header-bar {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 16px;
    margin-bottom: 12px;
    padding: 10px 14px;
    border-bottom: 1px solid #ebeef5;
  }

  .result-section-title-wrap {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .result-section-title {
    font-size: 15px;
    font-weight: 600;
    color: #0f172a;
  }

  .result-section-summary {
    color: #475569;
    line-height: 1.6;
    font-size: 13px;
  }

  .buttons-bar {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-shrink: 0;
  }

  .chart-select-container {
    display: flex;
    align-items: center;
  }

  .tool-btn {
    padding: 4px 8px;
    border-radius: 4px;
  }

  .tool-btn:hover {
    background-color: #f5f7fa;
  }

  .view-active {
    background-color: #ecf5ff;
    color: #409eff;
  }

  .result-show-area {
    width: 100%;
    min-height: 160px;
  }

  .reference-preview-card {
    margin: 0 16px 16px;
    padding: 16px;
    border-radius: 12px;
    border: 1px solid #d9ecff;
    background: linear-gradient(180deg, #f5fbff 0%, #eef7ff 100%);
  }

  .reference-preview-title {
    font-size: 14px;
    font-weight: 600;
    color: #1f2d3d;
    margin-bottom: 8px;
  }

  .reference-preview-summary {
    margin-bottom: 12px;
    color: #3c4a5d;
    line-height: 1.6;
  }

  .reference-preview-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 8px 12px;
  }

  .reference-preview-item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 10px 12px;
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.72);
  }

  .reference-preview-label {
    font-size: 12px;
    color: #6b7280;
  }

  .reference-preview-value {
    font-size: 13px;
    font-weight: 600;
    color: #111827;
    word-break: break-word;
  }
</style>
