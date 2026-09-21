import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';
import './reports.css';

// Reflect the OS colour-scheme preference onto <html data-theme>, which is
// what the token stylesheets key their dark values off. The app ships light
// by default; without this the designed [data-theme="dark"] tokens are never
// reachable. Kept preference-driven (no toggle UI) and live so it follows a
// system change; matchMedia is guarded for non-browser/test environments.
const prefersDark = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null;

function applyTheme(dark: boolean) {
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
}

if (prefersDark) {
  applyTheme(prefersDark.matches);
  prefersDark.addEventListener('change', (e) => applyTheme(e.matches));
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
