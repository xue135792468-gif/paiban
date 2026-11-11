export type DingTalkLoginReasonCode =
  | 'NOT_PROVISIONED'
  | 'AMBIGUOUS_IDENTITY'
  | 'ACCOUNT_DISABLED'
  | 'DINGTALK_DISABLED'
  | 'DINGTALK_CALL_FAILED'
  | 'ADMIN_BINDING_REQUIRED'

export interface DingTalkLoginNotice {
  code: DingTalkLoginReasonCode
  title: string
  description: string
}

const NOTICE_BY_CODE: Record<DingTalkLoginReasonCode, DingTalkLoginNotice> = {
  NOT_PROVISIONED: {
    code: 'NOT_PROVISIONED',
    title: '你还没有开通，请联系教务处',
    description: '系统未在教师档案中找到与你匹配的钉钉身份或教师姓名，请联系教务处或超级管理员为你建立教师档案并绑定钉钉。',
  },
  AMBIGUOUS_IDENTITY: {
    code: 'AMBIGUOUS_IDENTITY',
    title: '系统无法确认你是哪位老师，请联系教务处',
    description: '教师档案中存在同名老师且尚未完成钉钉绑定，为避免认错人，请联系教务处或超级管理员为您绑定钉钉账号。',
  },
  ACCOUNT_DISABLED: {
    code: 'ACCOUNT_DISABLED',
    title: '账号已停用',
    description: '你的排课系统账号当前处于停用状态，请联系教务处或超级管理员核实并重新启用。',
  },
  DINGTALK_DISABLED: {
    code: 'DINGTALK_DISABLED',
    title: '钉钉免登未启用',
    description: '学校尚未启用钉钉免登或未完成企业参数配置，请联系超级管理员或教务处处理。',
  },
  DINGTALK_CALL_FAILED: {
    code: 'DINGTALK_CALL_FAILED',
    title: '钉钉登录暂时无法完成',
    description: '无法从钉钉获取或验证你的登录身份，请稍后重试，或请联系教务处或超级管理员检查钉钉配置。',
  },
  ADMIN_BINDING_REQUIRED: {
    code: 'ADMIN_BINDING_REQUIRED',
    title: '需超级管理员确认绑定后才能免登',
    description: '该教师档案已关联管理员账号，首次钉钉绑定必须由超级管理员在「教师管理」或钉钉同步中明确确认，不能按同名自动开通。请联系超级管理员确认绑定。',
  },
}

export function resolveDingTalkLoginNotice(code?: string | null): DingTalkLoginNotice {
  if (code && Object.prototype.hasOwnProperty.call(NOTICE_BY_CODE, code)) {
    return NOTICE_BY_CODE[code as DingTalkLoginReasonCode]
  }
  return NOTICE_BY_CODE.DINGTALK_CALL_FAILED
}

export function extractDingTalkErrorCode(error: unknown): string | undefined {
  const data = (error as { response?: { data?: { code?: unknown } } } | null | undefined)?.response
    ?.data
  return typeof data?.code === 'string' ? data.code : undefined
}
