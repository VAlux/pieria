import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/console/',
  server: {
    host: '127.0.0.1',
    proxy: {
      '/v1': 'http://127.0.0.1:8077',
      '/pieria-health': 'http://127.0.0.1:8077',
      '/pieria-status': 'http://127.0.0.1:8077'
    }
  }
});
