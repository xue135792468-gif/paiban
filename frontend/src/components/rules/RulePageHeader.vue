<template>
  <header class="head">
    <div>
      <h2>排课规则</h2>
      <p class="subtitle">
        用「谁 / 什么课 / 什么时候 / 要求」拼一句规则。一张卡可以装 1–3 句，共用一个开关；
        保存后自动排课、草稿校验和调代都按同一份规则执行。
      </p>
    </div>
  </header>

  <section class="system-baseline" data-testid="rule-system-baseline">
    <div class="baseline-title">系统自带，不能关</div>
    <p>一个老师同一节只能在一个班上课</p>
    <p>一个班同一节只能上一门课</p>
  </section>
</template>

<style scoped>
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

h2 {
  margin: 0 0 4px;
  font-size: 20px;
}

.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}

.system-baseline {
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: var(--paiban-radius, 8px);
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--paiban-muted);
  font-size: 13px;
  line-height: 1.7;
}

.baseline-title {
  margin-bottom: 2px;
  color: var(--el-text-color-primary, #303133);
  font-size: 14px;
  font-weight: 600;
}

.system-baseline p {
  margin: 0;
}
</style>
