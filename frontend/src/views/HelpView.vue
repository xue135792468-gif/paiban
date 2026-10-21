<template>
  <div class="help-shell" :class="{ 'sidebar-closed': !sidebarOpen }">
    <aside v-show="sidebarOpen" class="help-aside" data-testid="help-sidebar">
      <div class="aside-head">
        <span class="aside-title">帮助中心</span>
        <span class="aside-sub">操作手册</span>
      </div>

      <el-collapse v-model="openGroups" class="doc-tree">
        <el-collapse-item v-for="group in HELP_GROUPS" :key="group.title" :title="group.title" :name="group.title">
          <router-link
            v-for="item in group.docs"
            :key="item.slug"
            :to="helpLink(item.slug)"
            class="doc-link"
            :class="{ active: item.slug === slug }"
          >
            {{ item.title }}
          </router-link>
        </el-collapse-item>
      </el-collapse>

      <div class="aside-foot">
        <router-link to="/" class="back-link">← 返回系统</router-link>
      </div>
    </aside>

    <div class="help-body">
      <header class="help-toolbar">
        <el-button class="tree-toggle" text @click="sidebarOpen = !sidebarOpen">
          <el-icon><Expand v-if="!sidebarOpen" /><Fold v-else /></el-icon>
          <span>目录</span>
        </el-button>

        <el-input
          v-model="query"
          class="help-search"
          placeholder="搜索帮助文档，例如：每周节数"
          clearable
          data-testid="help-search"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-button class="back-button" @click="router.push('/')">返回系统</el-button>
      </header>

      <main class="help-content">
        <div v-if="query.trim()" class="search-panel" data-testid="help-search-results">
          <h2 class="content-title">搜索结果</h2>
          <p class="search-summary">关键词「{{ query.trim() }}」共找到 {{ results.length }} 篇文档</p>
          <el-empty v-if="results.length === 0" description="没有找到相关内容，换个关键词试试" />
          <ul v-else class="result-list">
            <li v-for="result in results" :key="result.doc.slug" class="result-item">
              <a class="result-title" href="#" @click.prevent="openResult(result.doc.slug)">
                {{ result.doc.title }}
              </a>
              <span class="result-group">{{ result.doc.group }}</span>
              <p class="result-snippet">{{ result.snippet }}</p>
            </li>
          </ul>
        </div>

        <article v-else-if="doc" class="doc-article" data-testid="help-content">
          <p class="doc-group">{{ doc.group }}</p>
          <h1 class="content-title" data-testid="help-doc-title">{{ doc.title }}</h1>
          <p class="doc-summary">{{ doc.summary }}</p>
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div ref="articleEl" class="markdown-body" @click="onArticleClick" v-html="rendered" />
        </article>

        <div v-else-if="slug" class="not-found">
          <el-empty :description="`没有找到「${slug}」这篇帮助文档`">
            <el-button type="primary" @click="router.push('/help')">回到帮助中心</el-button>
          </el-empty>
        </div>

        <div v-else class="help-index" data-testid="help-index">
          <h1 class="content-title">帮助中心</h1>
          <p class="index-lead">
            按功能模块查找具体功能的用法，或按场景流程跟着步骤完成一次完整操作。文档支持打印，打印时会自动隐藏目录与工具栏。
          </p>

          <section v-for="group in HELP_GROUPS" :key="group.title" class="index-group">
            <h2 class="index-group-title">{{ group.title }}</h2>
            <div class="index-cards">
              <router-link
                v-for="item in group.docs"
                :key="item.slug"
                :to="helpLink(item.slug)"
                class="index-card"
              >
                <span class="index-card-title">{{ item.title }}</span>
                <span class="index-card-summary">{{ item.summary }}</span>
              </router-link>
            </div>
          </section>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Expand, Fold, Search } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import { HELP_GROUPS, findDoc, searchDocs } from '@/help/docs'
import { helpLink, type HelpSlug } from '@/help/links'

const route = useRoute()
const router = useRouter()

const md = new MarkdownIt({ html: true, linkify: false, breaks: false })

const sidebarOpen = ref(true)
const openGroups = ref<string[]>(HELP_GROUPS.map((group) => group.title))
const query = ref('')
const articleEl = ref<HTMLElement | null>(null)

const slug = computed(() => {
  const value = route.params.slug
  return Array.isArray(value) ? (value[0] ?? '') : (value ?? '')
})

const doc = computed(() => (slug.value ? findDoc(slug.value) : undefined))
const rendered = computed(() => (doc.value ? md.render(doc.value.body) : ''))
const results = computed(() => searchDocs(query.value))

function openResult(target: HelpSlug) {
  query.value = ''
  router.push(helpLink(target))
}

/** Internal app links in the markdown go through the router; external links stay native. */
function onArticleClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const anchor = target?.closest('a')
  if (!anchor) return
  const href = anchor.getAttribute('href') ?? ''
  // Only protocol-relative URLs (//host) are external; every root-relative path,
  // including /help/<slug>, must stay inside the SPA instead of reloading.
  if (!href.startsWith('/') || href.startsWith('//')) return
  event.preventDefault()
  router.push(href)
}

function scrollToTarget() {
  const hash = route.hash.replace(/^#/, '')
  if (hash) {
    const target = document.getElementById(hash)
    if (target) {
      target.scrollIntoView({ block: 'start' })
      return
    }
  }
  window.scrollTo({ top: 0 })
}

watch(
  () => route.fullPath,
  () => {
    if (route.path !== '/help' || route.hash) {
      query.value = ''
    }
    nextTick(scrollToTarget)
  },
)

onMounted(() => {
  nextTick(scrollToTarget)
})
</script>

<style scoped>
.help-shell {
  display: flex;
  min-height: 100vh;
  background: var(--paiban-bg, #f5f7fb);
  color: #1f2937;
}
.help-aside {
  width: 260px;
  flex: 0 0 260px;
  background: #fff;
  border-right: 1px solid #eef0f5;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
.aside-head {
  padding: 22px 20px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.aside-title {
  font-size: 17px;
  font-weight: 700;
}
.aside-sub {
  font-size: 12px;
  color: #9aa1b1;
  letter-spacing: 0.08em;
}
.doc-tree {
  border-top: none;
  padding: 0 12px;
}
.doc-tree :deep(.el-collapse-item__header) {
  font-weight: 600;
  color: #4b5563;
  padding-left: 8px;
}
.doc-tree :deep(.el-collapse-item__content) {
  padding-bottom: 8px;
}
.doc-link {
  display: block;
  padding: 7px 12px 7px 24px;
  font-size: 13px;
  color: #4b5563;
  text-decoration: none;
  border-radius: 7px;
  line-height: 1.5;
}
.doc-link:hover {
  background: #f4f6ff;
  color: #3f5ae0;
}
.doc-link.active {
  background: #eef1ff;
  color: #3f5ae0;
  font-weight: 600;
}
.aside-foot {
  margin-top: auto;
  padding: 16px 20px 24px;
  border-top: 1px solid #f1f2f6;
}
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
}
.back-link:hover {
  color: #3f5ae0;
}
.help-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.help-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 32px;
  background: #fff;
  border-bottom: 1px solid #eef0f5;
  position: sticky;
  top: 0;
  z-index: 5;
}
.tree-toggle {
  color: #6b7280;
}
.help-search {
  max-width: 420px;
}
.back-button {
  margin-left: auto;
}
.help-content {
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
  padding: 32px 32px 72px;
  box-sizing: border-box;
}
.content-title {
  font-size: 26px;
  font-weight: 700;
  margin: 0 0 8px;
}
.doc-group {
  font-size: 12px;
  color: #9aa1b1;
  letter-spacing: 0.08em;
  margin: 0 0 6px;
}
.doc-summary {
  color: #6b7280;
  margin: 0 0 24px;
}
.index-lead {
  color: #6b7280;
  line-height: 1.7;
  margin: 0 0 28px;
}
.index-group {
  margin-bottom: 28px;
}
.index-group-title {
  font-size: 15px;
  color: #4b5563;
  margin: 0 0 12px;
}
.index-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
.index-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #eef0f5;
  border-radius: 10px;
  text-decoration: none;
  color: inherit;
}
.index-card:hover {
  border-color: #c7d0ff;
  box-shadow: 0 4px 14px rgba(79, 110, 247, 0.08);
}
.index-card-title {
  font-weight: 600;
}
.index-card-summary {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}
.search-panel {
  background: #fff;
  border: 1px solid #eef0f5;
  border-radius: 12px;
  padding: 24px;
}
.search-summary {
  color: #6b7280;
  font-size: 13px;
  margin: 0 0 16px;
}
.result-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.result-item {
  padding: 14px 0;
  border-top: 1px solid #f1f2f6;
}
.result-title {
  font-weight: 600;
  color: #3f5ae0;
  text-decoration: none;
}
.result-group {
  margin-left: 10px;
  font-size: 12px;
  color: #9aa1b1;
}
.result-snippet {
  margin: 6px 0 0;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.6;
}
.markdown-body {
  background: #fff;
  border: 1px solid #eef0f5;
  border-radius: 12px;
  padding: 28px 32px;
  line-height: 1.85;
  font-size: 14px;
}
.markdown-body :deep(h1) {
  font-size: 24px;
  margin: 8px 0 16px;
}
.markdown-body :deep(h2) {
  font-size: 19px;
  margin: 32px 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid #f1f2f6;
  scroll-margin-top: 72px;
}
.markdown-body :deep(h3) {
  font-size: 16px;
  margin: 22px 0 10px;
}
.markdown-body :deep(p) {
  margin: 10px 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 22px;
}
.markdown-body :deep(li) {
  margin: 4px 0;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 14px 0;
  font-size: 13px;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #eef0f5;
  padding: 8px 10px;
  text-align: left;
}
.markdown-body :deep(th) {
  background: #f8f9fc;
}
.markdown-body :deep(a) {
  color: #3f5ae0;
}
.markdown-body :deep(blockquote) {
  margin: 14px 0;
  padding: 8px 16px;
  border-left: 3px solid #c7d0ff;
  background: #f8f9ff;
  color: #4b5563;
}
.markdown-body :deep(code) {
  background: #f3f4f6;
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 12.5px;
}

@media print {
  .help-aside,
  .help-toolbar {
    display: none !important;
  }
  .help-shell {
    display: block;
    background: #fff;
  }
  .help-content {
    max-width: none;
    padding: 0;
  }
  .markdown-body {
    border: none;
    padding: 0;
    font-size: 12pt;
  }
  .doc-article {
    display: block;
  }
}
</style>
