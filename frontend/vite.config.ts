import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev the API runs on the host port published by Docker Compose.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8480' } },
})
