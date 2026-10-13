import defaultTheme from 'tailwindcss/defaultTheme';

// Connecty design system tokens (connecty-ui-ux-design-system/project/design-system/01-foundations.md).
// The palette is Tailwind's own slate/blue/green/amber/violet/red - #0F172A = slate-900, #475569 = slate-600,
// #94A3B8 = slate-400, #E2E8F0 = slate-200, #F1F5F9 = slate-100, #F8FAFC = slate-50, #2563EB = blue-600,
// #1D4ED8 = blue-700 - so pages use the stock color classes; only the values the stock palette lacks are added.
/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      // R17-04: <body class="font-sans"> (index.html) outranks the plain `body { font-family }` rule in
      // index.css, so the app font has to be the Tailwind sans stack itself or it is never applied.
      fontFamily: {
        sans: ['"Inter Variable"', 'Inter', '-apple-system', '"SF Pro Text"', '"Segoe UI"', 'Roboto', ...defaultTheme.fontFamily.sans],
      },
      // Integer sizes only (the design forbids .5px sizes). [size, { lineHeight, letterSpacing }].
      fontSize: {
        display: ['40px', { lineHeight: '48px', letterSpacing: '-0.8px' }],
        h1: ['32px', { lineHeight: '40px', letterSpacing: '-0.6px' }],
        h2: ['24px', { lineHeight: '32px', letterSpacing: '-0.3px' }],
        'h2-sm': ['20px', { lineHeight: '28px', letterSpacing: '-0.2px' }],
        h3: ['17px', { lineHeight: '25px' }],
        'h3-lg': ['18px', { lineHeight: '26px' }],
        body: ['16px', { lineHeight: '26px' }],
        'body-sm': ['15px', { lineHeight: '23px' }],
        reading: ['17px', { lineHeight: '29px' }],
        ui: ['14px', { lineHeight: '20px' }],
        meta: ['13px', { lineHeight: '18px' }],
        caption: ['12px', { lineHeight: '16px' }],
        micro: ['11px', { lineHeight: '14px' }],
        'micro-xs': ['10px', { lineHeight: '12px' }],
      },
      colors: {
        tint: '#EFF4FD',          // selected / active row background (border blue-100 or blue-200)
        'warn-soft': '#FFF7E8',   // streak / assignment / warning chip background (text amber-800)
        'line-soft': '#EDF1F6',   // skeleton second bar
        canvas: '#E9EEF5',
        // D-35 (UI shell): the prototype's ink scale and surfaces (same values as the CSS variables in index.css).
        ink: {
          900: '#0F172A',
          700: '#334155',
          600: '#475569',
          500: '#64748B',
          400: '#94A3B8', // icons / borders only - text uses ink-500 or darker (WCAG AA)
          300: '#CBD5E1',
          200: '#E2E8F0',
        },
        line: '#EEF1F5',
        'line-card': '#E5E9F0',
        'surface-muted': '#F1F5F9',
        'surface-subtle': '#F8FAFC',
        track: '#EEF2F7',
        submit: '#F59E0B',
        'ans-correct': { bg: '#F0FDF4', bd: '#4ADE80', fg: '#15803D' },
        'ans-wrong': { bg: '#FEF2F2', bd: '#F87171', fg: '#B91C1C' },
        'ans-blank': { bg: '#FFFBEB', bd: '#FCD34D', fg: '#92400E' },
        'skill-gv': { bg: '#F5F3FF', fg: '#6D28D9' },
        'skill-listening': { bg: '#FFFBEB', fg: '#B45309' },
        'skill-reading': { bg: '#EFF6FF', fg: '#1D4ED8' },
        'skill-speaking': { bg: '#ECFDF3', fg: '#15803D' },
        'skill-writing': { bg: '#FDF2F8', fg: '#BE185D' },
        'skill-focus': { bg: '#F1F5F9', fg: '#0F172A' },
      },
      borderRadius: {
        btn: '12px',
        input: '12px',
        thumb: '10px',
        card: '20px',
        section: '24px',
        modal: '24px',
        community: '14px', // square-ish community avatar - never round (round = a person)
        panel: '20px',
      },
      boxShadow: {
        hairline: '0 1px 2px rgba(15,23,42,0.04)',
        e1: '0 8px 28px rgba(15,23,42,0.06)',
        lift: '0 12px 32px rgba(15,23,42,0.10)',
        modal: '0 24px 64px rgba(15,23,42,0.18)',
        dropdown: '0 18px 44px rgba(15,23,42,0.20)',
        float: '0 30px 60px -30px rgba(15,23,42,0.35)',
        toast: '0 10px 30px rgba(15,23,42,0.30)',
      },
      keyframes: {
        'ap-in': { from: { opacity: '0', transform: 'translateY(14px)' }, to: { opacity: '1', transform: 'none' } },
        'ap-drop': { from: { opacity: '0', transform: 'translateY(-6px) scale(.98)' }, to: { opacity: '1', transform: 'none' } },
      },
      animation: {
        'ap-in': 'ap-in .45s cubic-bezier(.2,.8,.2,1) both',
        'ap-drop': 'ap-drop .2s cubic-bezier(.2,.8,.2,1) both',
      },
      maxWidth: {
        container: '1200px',
        reading: '720px',
      },
      transitionTimingFunction: {
        out: 'cubic-bezier(0, 0, 0.2, 1)',
        'ap-out': 'cubic-bezier(.2,.8,.2,1)',
      },
      transitionDuration: {
        micro: '120ms',
        state: '200ms',
        spatial: '300ms',
      },
    },
  },
  plugins: [],
}
