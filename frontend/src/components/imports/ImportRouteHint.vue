<template>
  <el-alert
    data-testid="import-recommended-paths"
    type="info"
    show-icon
    :closable="false"
    class="notice"
  >
    <template #title>
      <span class="recommended">
        常用路径：手动选列导入 / 配方导入（推荐）。本页的 AI 向导只用于遇到新格式时帮你写一份配方。
      </span>
      <router-link to="/data/import/manual" class="recommended-link">手动选列导入</router-link>
      <router-link to="/data/recipes" class="recommended-link">配方导入</router-link>
    </template>
  </el-alert>
</template>

<script setup lang="ts">
/**
 * 导入页顶部的入口提示：手动选列与配方是日常路径，AI 向导只负责新格式写配方。
 * 单独成组件，既让这条说明只有一个定义，也不把导入页撑过 SFC 行数红线。
 */
</script>

<style scoped>
.notice {
  margin-bottom: 4px;
}
.recommended {
  margin-right: 12px;
}
.recommended-link + .recommended-link {
  margin-left: 12px;
}
</style>
