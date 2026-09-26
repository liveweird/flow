import { execSync } from 'node:child_process'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

function git(args: string): string {
  try {
    return execSync(`git ${args}`, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
  } catch {
    return ''
  }
}

// Build stamp shown by src/components/VersionStamp.tsx. Env vars win so the Docker
// build (whose worktree never matches the index — a `git status` dirty check there
// would always be a false positive) and CI can inject exact values; local builds
// fall back to git, marking uncommitted state with "+dirty".
const sha = git('rev-parse --short HEAD')
const commit =
  process.env.GIT_SHA || (sha ? (git('status --porcelain') ? `${sha}+dirty` : sha) : 'unknown')
const commitTime = process.env.GIT_COMMIT_TIME || git('log -1 --format=%cI') || ''

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    __APP_COMMIT__: JSON.stringify(commit),
    __APP_COMMIT_TIME__: JSON.stringify(commitTime),
  },
  server: {
    // 5176 — Flow's own port, beside the sibling stacks' 5173–5175.
    port: 5176,
    proxy: {
      '/api': 'http://localhost:8084',
    },
  },
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            // Groups capture their transitive deps too; keeping React (+scheduler) in its own
            // high-priority chunk stops future lazy-loaded feature chunks from swallowing it
            // and importing it eagerly everywhere.
            {
              name: 'react',
              test: /node_modules[\\/](?:react|react-dom|scheduler)[\\/]/,
              priority: 10,
            },
          ],
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'happy-dom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/api/schema.ts',
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        'src/main.tsx',
        'src/vite-env.d.ts',
        '**/*.d.ts',
      ],
      // Floors set just below current measured coverage so they gate regressions without
      // blocking unrelated work. Raise as coverage improves, never lower.
      // (2026-09-26: measured after trimming the donor app's domain features down to Flow's —
      // actuals lines 96.90 / statements 94.09 / functions 90.30 / branches 89.58.)
      thresholds: {
        lines: 95,
        statements: 92,
        functions: 88,
        branches: 87,
      },
    },
  },
})
