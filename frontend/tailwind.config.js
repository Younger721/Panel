/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        primary: {
          50: '#eef2ff',
          100: '#e0e7ff',
          200: '#c7d2fe',
          300: '#a5b4fc',
          400: '#818cf8',
          500: '#6366f1',
          600: '#4f46e5',
          700: '#4338ca',
          800: '#3730a3',
          900: '#312e81'
        }
      },
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'PingFang SC',
          'Hiragino Sans GB',
          'Microsoft YaHei',
          'Helvetica Neue',
          'Helvetica',
          'Arial',
          'sans-serif'
        ]
      },
      boxShadow: {
        card: '0 2px 8px -2px rgba(15, 23, 42, 0.06), 0 12px 28px -14px rgba(15, 23, 42, 0.18)',
        'card-hover': '0 12px 36px -12px rgba(15, 23, 42, 0.22)',
        paper: '0 1px 3px rgba(15, 23, 42, 0.06), 0 24px 48px -24px rgba(15, 23, 42, 0.2)'
      },
      keyframes: {
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(12px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' }
        }
      },
      animation: {
        'fade-up': 'fade-up 0.35s ease-out both'
      }
    }
  },
  plugins: []
}
