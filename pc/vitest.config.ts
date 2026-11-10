import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['packages/*/test/**/*.test.ts'],
    testTimeout: 15000,
    hookTimeout: 15000,
    env: { PUPUS_LOG_LEVEL: 'error' },
  },
});
