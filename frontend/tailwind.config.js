/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: '#1f2937',
        paper: '#ffffff',
        strong: '#059669',
        medium: '#d97706',
        weak: '#dc2626'
      },
      boxShadow: {
        paper: '0 1px 3px rgba(15, 23, 42, 0.08), 0 12px 32px -12px rgba(15, 23, 42, 0.18)'
      }
    }
  },
  plugins: []
}
