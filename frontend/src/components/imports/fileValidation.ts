export const SPREADSHEET_EXTENSIONS = ['.xlsx', '.xls', '.csv']

export function spreadsheetFileError(file: Pick<File, 'name'>): string | null {
  const lower = file.name.toLowerCase()
  if (SPREADSHEET_EXTENSIONS.some((extension) => lower.endsWith(extension))) return null
  return `只支持 .xlsx / .xls / .csv 格式的文件，当前选择的是 ${file.name}。`
}
