import { driver, type Config, type DriveStep } from 'driver.js'
import 'driver.js/dist/driver.css'

export const TOUR_SEEN_KEY = 'paiban.tour.seen'
export const TOUR_ADVANCED_KEY = 'paiban.tour.advanced'

export type TourStage = 'basic' | 'advanced'

interface TourStepDefinition {
  /** Menu path highlighted by this step; omitted for the centred welcome/done cards. */
  target?: string
  title: string
  description: string
}

/**
 * Steps are addressed through the `data-tour` path markers on the sidebar menu, so
 * the tour keeps working while every page below the layout is rewritten.
 */
const BASIC_STEPS: TourStepDefinition[] = [
  {
    title: '欢迎使用排课系统',
    description: '这个引导会带你认识几个核心入口，大约需要一分钟。随时可以点「跳过教程」。',
  },
  {
    target: '/admin/classes',
    title: '基础数据',
    description: '年级、行政班、教师、教室、课程都在「教务」分组里维护，也支持 Excel 导入。',
  },
  {
    target: '/admin/scheduling',
    title: '自动排课',
    description: '配置好约束后，在这里提交排课并查看生成的课表草稿。',
  },
  {
    target: '/substitutions',
    title: '调代课',
    description: '教师请假与代课申请在这里提交和审批，结果会叠加到课表上。',
  },
  {
    target: '/timetable',
    title: '课表查看',
    description: '按行政班、教师、教室、全校四种视角查看课表，并导出 Excel 或打印。',
  },
  {
    title: '开始使用',
    description: '教程随时可以在「设置中心 → 新手教程」重新打开。',
  },
]

const ADVANCED_STEPS: TourStepDefinition[] = [
  {
    target: '/admin/constraints',
    title: '约束权重',
    description: '约束权重 0-100 表示优先级，越高越优先满足；硬约束不满足时排课会失败。',
  },
  {
    target: '/admin/scheduling',
    title: '提交排课',
    description: '点击「开始排课」提交求解，完成后可以打开草稿手工微调。',
  },
  {
    target: '/timetable',
    title: '课表导出',
    description: '课表查看页可以按当前视角导出 Excel，发布后的课表支持打印为 PDF。',
  },
]

function storageKey(stage: TourStage): string {
  return stage === 'advanced' ? TOUR_ADVANCED_KEY : TOUR_SEEN_KEY
}

/** Read the "already watched" flag; a broken localStorage counts as seen so the tour never nags. */
export function hasSeenTour(stage: TourStage = 'basic'): boolean {
  try {
    return localStorage.getItem(storageKey(stage)) === '1'
  } catch {
    return true
  }
}

export function markTourSeen(stage: TourStage = 'basic'): void {
  try {
    localStorage.setItem(storageKey(stage), '1')
  } catch {
    // private mode / quota: the tour simply shows again next time
  }
}

/** Forget both stages so the basic tour can be replayed from the settings page. */
export function resetTour(): void {
  try {
    localStorage.removeItem(TOUR_SEEN_KEY)
    localStorage.removeItem(TOUR_ADVANCED_KEY)
  } catch {
    // ignore
  }
}

function isRendered(target: string): boolean {
  return document.querySelector(`[data-tour="${target}"]`) !== null
}

/** Only one tour overlay may exist at a time; a new one replaces whatever is running. */
let activeDriver: ReturnType<typeof driver> | undefined

/** Missing menu entries (permissions, collapsed groups) are dropped instead of failing the whole tour. */
function buildSteps(stage: TourStage): DriveStep[] {
  const definitions = stage === 'advanced' ? ADVANCED_STEPS : BASIC_STEPS
  return definitions
    .filter((definition) => !definition.target || isRendered(definition.target))
    .map((definition) => ({
      element: definition.target ? `[data-tour="${definition.target}"]` : undefined,
      popover: { title: definition.title, description: definition.description },
    }))
}

export function startTour(stage: TourStage): void {
  const steps = buildSteps(stage)
  if (steps.length === 0) return

  if (activeDriver?.isActive()) {
    activeDriver.destroy()
  }

  const config: Config = {
    steps,
    showProgress: true,
    progressText: '{{current}} / {{total}}',
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '开始使用',
    closeBtnLabel: '跳过教程',
    allowClose: true,
    overlayClickBehavior: 'none',
    skipMissingElement: true,
    // driver.js skips onDestroyed when the tour is closed before its first highlight
    // settles, so the explicit buttons record the stage themselves.
    onCloseClick: (_element, _step, opts) => {
      markTourSeen(stage)
      opts.driver.destroy()
    },
    onDoneClick: (_element, _step, opts) => {
      markTourSeen(stage)
      opts.driver.destroy()
    },
    onDestroyed: () => {
      markTourSeen(stage)
      activeDriver = undefined
    },
  }

  activeDriver = driver(config)
  activeDriver.drive()
}
