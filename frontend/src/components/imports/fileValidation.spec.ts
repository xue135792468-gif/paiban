import { describe, expect, it } from 'vitest'
import { spreadsheetFileError } from './fileValidation'

describe('spreadsheetFileError', () => {
  it('accepts Excel and CSV in any case', () => {
    for (const name of ['a.xlsx', 'B.XLS', 'c.CsV']) {
      expect(spreadsheetFileError({ name })).toBeNull()
    }
  })

  it('names the rejected file and the supported formats', () => {
    expect(spreadsheetFileError({ name: 'notes.txt' })).toBe(
      '只支持 .xlsx / .xls / .csv 格式的文件，当前选择的是 notes.txt。',
    )
  })
})
