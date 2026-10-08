# APG merchant dashboard (React + Vite + TypeScript)

Dev: start the backend (`scripts/dev-start.sh`), then `npm install && npm run dev` -> http://localhost:5173.
`/api` is proxied to the gateway on :8080. Access tokens are kept in memory only; the refresh token in sessionStorage.
Secrets (API key secret, webhook signing secret) are shown once and never stored by the UI.
