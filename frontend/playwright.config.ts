import fs from 'node:fs'
import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

const backendCommand =
  process.platform === 'win32' ? 'mvnw.cmd -q spring-boot:run -Dspring-boot.run.profiles=e2e' : './mvnw -q spring-boot:run -Dspring-boot.run.profiles=e2e'

// Outside CI the repo keeps its JDK under .tools; hand it to the spawned Maven
// so `npm run test:e2e` works from a shell that never sourced .tools/env.sh.
const toolsDir = path.resolve(process.cwd(), '../.tools')
const localJdk = fs.existsSync(toolsDir)
  ? fs.readdirSync(toolsDir).find((entry) => entry.startsWith('jdk-'))
  : undefined
const backendEnv = localJdk
  ? ({ ...process.env, JAVA_HOME: path.join(toolsDir, localJdk) } as Record<string, string>)
  : undefined

export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: backendCommand,
      cwd: '../backend',
      url: 'http://127.0.0.1:8080/api/health',
      timeout: 300_000,
      reuseExistingServer: true,
      stdout: 'ignore',
      stderr: 'pipe',
      ...(backendEnv ? { env: backendEnv } : {}),
    },
    {
      command: 'npm run dev -- --host 127.0.0.1',
      cwd: '.',
      url: 'http://127.0.0.1:5173',
      timeout: 120_000,
      reuseExistingServer: true,
    },
  ],
})
