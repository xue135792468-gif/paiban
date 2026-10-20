import { useRoute, useRouter } from 'vue-router'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

/**
 * Landing rules shared by every way into the app (password, DingTalk, Feishu login
 * and Feishu binding): honour a redirect target, but on the home page show the
 * first-run onboarding wizard to administrators who can manage it.
 */
export function useLoginLanding() {
  const route = useRoute()
  const router = useRouter()
  const auth = useAuthStore()

  async function landAfterLogin(fallback = '/') {
    const redirect = (route.query.redirect as string) || fallback
    if (redirect === '/') {
      try {
        const { data } = await http.get('/onboarding/status')
        if (data.showWizard && auth.hasPermission('onboarding:manage')) {
          router.replace('/onboarding')
          return
        }
      } catch {
        // fall through to the normal landing page
      }
    }
    router.replace(redirect)
  }

  return { landAfterLogin }
}
