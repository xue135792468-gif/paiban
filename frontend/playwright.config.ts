import fs from 'node:fs'
import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

// PAIBAN_E2E_ISOLATED=1 keeps the suite off 8080/5173 so a running dev server
// can never be reused (and silently tested) instead of this checkout.
const isolated = process.env.PAIBAN_E2E_ISOLATED === '1'
const backendPort = isolated ? Number(process.env.PAIBAN_E2E_BACKEND_PORT || 28080) : 8080
const frontendPort = isolated ? Number(process.env.PAIBAN_E2E_FRONTEND_PORT || 25173) : 5173
const backendUrl = `http://127.0.0.1:${backendPort}`
const frontendUrl = `http://127.0.0.1:${frontendPort}`

if (isolated && !process.env.PAIBAN_E2E_API) {
  process.env.PAIBAN_E2E_API = `${backendUrl}/api`
}

const backendCommand =
  process.platform === 'win32' ? 'mvnw.cmd -q spring-boot:run -Dspring-boot.run.profiles=e2e' : './mvnw -q spring-boot:run -Dspring-boot.run.profiles=e2e'

// Outside CI the repo keeps its JDK under .tools; hand it to the spawned Maven
// so `npm run test:e2e` works from a shell that never sourced .tools/env.sh.
const toolsDir = path.resolve(process.cwd(), '../.tools')
const localJdk = fs.existsSync(toolsDir)
  ? fs.readdirSync(toolsDir).find((entry) => entry.startsWith('jdk-'))
  : undefined
const backendEnv = {
  ...process.env,
  ...(localJdk ? { JAVA_HOME: path.join(toolsDir, localJdk) } : {}),
  ...(isolated
    ? { SERVER_PORT: String(backendPort), PAIBAN_CORS_ALLOWED_ORIGINS: frontendUrl }
    : {}),
} as Record<string, string>
const frontendEnv = isolated
  ? ({
      ...process.env,
      PAIBAN_DEV_PORT: String(frontendPort),
      PAIBAN_API_TARGET: backendUrl,
    } as Record<string, string>)
  : undefined

export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: frontendUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: backendCommand,
      cwd: '../backend',
      url: `${backendUrl}/api/health`,
      timeout: 300_000,
      reuseExistingServer: !isolated,
      stdout: 'pipe',
      stderr: 'pipe',
      env: backendEnv,
    },
    {
      command: 'npm run dev -- --host 127.0.0.1',
      cwd: '.',
      url: frontendUrl,
      timeout: 120_000,
      reuseExistingServer: !isolated,
      ...(frontendEnv ? { env: frontendEnv } : {}),
    },
  ],
})
