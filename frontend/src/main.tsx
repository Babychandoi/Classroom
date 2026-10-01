import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
// R17-04: Plus Jakarta Sans is self-hosted (woff2 files are bundled by Vite and served from our own
// origin). It used to come from fonts.googleapis.com, which the CSP (style-src/font-src 'self') blocks
// on every page - keep the CSP tight rather than opening it to an external font host.
import '@fontsource-variable/plus-jakarta-sans';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
