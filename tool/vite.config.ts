import { defineConfig } from 'vite';

// Tauri serves the dev build on a fixed port and reads the production build from dist/.
export default defineConfig({
  clearScreen: false,
  server: { port: 5280, strictPort: true },
  build: { target: 'es2022', outDir: 'dist', emptyOutDir: true },
});
