import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev only: forward API calls to the APG gateway so the browser sees a single origin (no CORS).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
