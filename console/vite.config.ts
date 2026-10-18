import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// `PIERIA_DAEMON_URL=http://127.0.0.1:8078 npm run dev` points the dev server at another daemon.
const daemon = process.env.PIERIA_DAEMON_URL ?? 'http://127.0.0.1:8077';

export default defineConfig({
  plugins: [react()],
  base: '/console/',
  server: {
    host: '127.0.0.1',
    proxy: {
      '/v1': daemon,
      '/pieria-health': daemon,
      '/pieria-status': daemon
    }
  },
  test: {
    environment: 'happy-dom',
    setupFiles: ['src/test/setup.ts'],
    css: { modules: { classNameStrategy: 'non-scoped' } }
  }
});
