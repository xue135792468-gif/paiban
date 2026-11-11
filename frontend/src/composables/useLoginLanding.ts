import { useRoute, useRouter } from 'vue-router'
import { canManageDrafts } from '@/constants/permissions'
import { workbenchLandingPath } from '@/layouts/navigation'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

/**
 * Landing rules shared by every way into the app (password and DingTalk login):
 * honour a redirect target, but on the home page show the first-run onboarding
 * wizard to administrators who can manage it.
 */
export function useLoginLanding() {
  const route = useRoute()
  const router = useRouter()
  const auth = useAuthStore()

  async function landAfterLogin(fallback = '/', isCurrent: () => boolean = () => true) {
    // isCurrent 可选：调用方传入自己的失效判定，落地前的 await 之后再复核一次，
    // 避免已经在别处登录（如切到密码入口）的用户被这次旧落地再带走。
    const redirect = (route.query.redirect as string) || fallback
    if (redirect === '/') {
      // UserStory19：老师默认看到自己的课表。判据与导航/课表页同一套能力（不看 role 字符串）：
      // 已关联教师档案、能看本人课表、且不是排课/编辑管理者。
      const isOwnTimetableTeacher =
        auth.user?.teacherId != null &&
        auth.hasPermission('timetable:view:own') &&
        !canManageDrafts((permission) => auth.hasPermission(permission))
      if (isOwnTimetableTeacher) {
        if (!isCurrent()) return
        router.replace(workbenchLandingPath((permission) => auth.hasPermission(permission)))
        return
      }
      try {
        const { data } = await http.get('/onboarding/status')
        if (!isCurrent()) return
        if (data.showWizard && auth.hasPermission('onboarding:manage')) {
          router.replace('/onboarding')
          return
        }
      } catch {
        // fall through to the normal landing page
      }
    }
    if (!isCurrent()) return
    router.replace(redirect)
  }

  return { landAfterLogin }
}
