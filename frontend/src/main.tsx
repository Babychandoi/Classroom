import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
// R17-04: the app font (Inter, per the Connecty design system) is self-hosted - woff2 files are bundled
// by Vite and served from our own origin. The design loads it from fonts.googleapis.com, which the CSP
// (style-src/font-src 'self') blocks on every page - keep the CSP tight rather than opening it.
import '@fontsource-variable/inter';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
