import js from '@eslint/js'
import globals from 'globals'
import sonarjs from 'eslint-plugin-sonarjs'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

// The e2e workspace's quality gate — the sibling of web/eslint.config.js:
// zero findings, no baseline, one commented override per deliberate idiom.
export default defineConfig([
  globalIgnores(['node_modules', 'playwright-report', 'test-results', '.playwright']),
  {
    files: ['**/*.ts', '**/*.mjs'],
    extends: [js.configs.recommended, tseslint.configs.recommended, sonarjs.configs.recommended],
    languageOptions: {
      globals: globals.node,
    },
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // The suite drives http://localhost:8084 — the compose demo is plain HTTP by design.
      'sonarjs/no-clear-text-protocols': 'off',
      // Test titles are literal sentences mirrored verbatim by the scenario files (scenarios/README.md).
      'sonarjs/parameterized-tests': 'off',
      // The seed admin's demo password is the documented compose credential, not a secret.
      'sonarjs/no-hardcoded-passwords': 'off',
      // global-setup/teardown shell out to `docker compose` by name — the documented run recipe.
      'sonarjs/no-os-command-from-path': 'off',
      // Mantine's Switch/Checkbox/SegmentedControl inputs are visually hidden; `force: true` on
      // them is the documented idiom (mfa.spec.ts), never a way around a disabled control.
      'sonarjs/no-forced-browser-interaction': 'off',
      // A journey test is one long function by design (numbered user steps); backstops only.
      'sonarjs/cognitive-complexity': ['error', 25],
      complexity: ['error', 30],
      'max-lines-per-function': ['error', { max: 250, skipBlankLines: true, skipComments: true }],
      'max-depth': ['error', 5],
      'max-params': ['error', 6],
    },
  },
])
