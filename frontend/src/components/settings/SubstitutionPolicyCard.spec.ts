import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElFormItem, ElOption } from 'element-plus'
import SubstitutionPolicyCard from './SubstitutionPolicyCard.vue'

function mountCard() {
  return mount(SubstitutionPolicyCard, {
    props: {
      modelValue: {
        'substitution.approval-mode': 'approval',
        'substitution.search-window-days': '7',
        'substitution.max-plan-teachers': '6',
        'substitution.ranking-preset': 'min-impact',
        'notification.scope': 'minimal',
      },
    },
    global: { plugins: [ElementPlus] },
  })
}

function formItem(wrapper: ReturnType<typeof mountCard>, label: string) {
  const item = wrapper
    .findAllComponents(ElFormItem)
    .find((candidate) => candidate.props('label') === label)
  if (!item) {
    throw new Error(`form item not found: ${label}`)
  }
  return item
}

describe('共用调课策略设置卡', () => {
  it('把方案教师上限更名为最多变动几节课', () => {
    const wrapper = mountCard()

    expect(wrapper.text()).toContain('最多变动几节课')
    expect(wrapper.text()).not.toContain('方案教师上限')
  })

  it('上限仍是 2 到 10 节，默认 6 节，并说明去重教师数另计', () => {
    const wrapper = mountCard()
    const item = formItem(wrapper, '最多变动几节课')

    const options = item.findAllComponents(ElOption)
    expect(options.map((option) => option.props('value'))).toEqual(
      ['2', '3', '4', '5', '6', '7', '8', '9', '10'],
    )
    expect(options.map((option) => option.props('label'))).toEqual(
      ['2 节', '3 节', '4 节', '5 节', '6 节', '7 节', '8 节', '9 节', '10 节'],
    )
    expect(item.text()).toContain('默认 6 节')
    expect(item.text()).toContain('一次换位方案最多移动这么多节课；去重教师人数另计')
    expect(item.text()).toContain('草稿换位与发布后调课的新方案都按此上限')
  })

  it('搜索窗口只用于发布后调课，草稿换位始终在本周内搜索', () => {
    const wrapper = mountCard()
    const item = formItem(wrapper, '调课搜索窗口')

    expect(item.text()).toContain('发布后调课先找同一天')
    expect(item.text()).toContain('草稿换位始终在本周内搜索')
    expect(item.text()).not.toContain('无解再向后')
  })

  it('排序预设说明草稿与发布后调课共用', () => {
    const wrapper = mountCard()
    const item = formItem(wrapper, '方案排序')

    expect(item.text()).toContain('草稿换位与发布后调课的新方案都按此排序')
  })
})
