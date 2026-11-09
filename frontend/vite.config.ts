import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev: /api is proxied to Spring Boot, so the browser sees one origin (no CORS needed).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // ../shared holds the API module shared with the mobile app.
  server: { fs: { allow: ['..'] }, proxy: { '/api': process.env.API_URL ?? 'http://localhost:8080' } },
})
