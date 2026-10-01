/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // R17-04: Vite inlines assets under 4 KB as data: URIs, which the small unicode-range slices of the
    // self-hosted font hit - and the CSP (font-src falls back to default-src 'self') blocks data: fonts.
    // Fonts are always emitted as real files served from our own origin instead of loosening the CSP.
    assetsInlineLimit: (filePath: string) => (/\.(woff2?|ttf|otf|eot)$/i.test(filePath) ? false : undefined),
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
  server: {
    port: 3000,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
});
