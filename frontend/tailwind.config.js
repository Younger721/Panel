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
        },
        rise: {
          '0%': { opacity: '0', transform: 'translateY(18px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' }
        },
        // 登录页：简历预览卡缓慢浮动（角度写进关键帧，避免和 rotate 工具类互相覆盖）
        'auth-float': {
          '0%, 100%': { transform: 'translateY(0) rotate(-2.4deg)' },
          '50%': { transform: 'translateY(-9px) rotate(-1.5deg)' }
        },
        'auth-float-loose': {
          '0%, 100%': { transform: 'translateY(0) rotate(1.6deg)' },
          '50%': { transform: 'translateY(-6px) rotate(2.4deg)' }
        },
        // 批注高亮从左向右扫出，停一会再重来
        sweep: {
          '0%': { transform: 'scaleX(0)' },
          '16%': { transform: 'scaleX(1)' },
          '82%': { transform: 'scaleX(1)' },
          '100%': { transform: 'scaleX(0)' }
        },
        shake: {
          '0%, 100%': { transform: 'translateX(0)' },
          '20%': { transform: 'translateX(-4px)' },
          '40%': { transform: 'translateX(4px)' },
          '60%': { transform: 'translateX(-2px)' },
          '80%': { transform: 'translateX(2px)' }
        }
      },
      animation: {
        'fade-up': 'fade-up 0.35s ease-out both',
        rise: 'rise 0.6s cubic-bezier(0.22, 1, 0.36, 1) both',
        'auth-float': 'auth-float 9s ease-in-out infinite',
        'auth-float-loose': 'auth-float-loose 7s ease-in-out infinite',
        sweep: 'sweep 5.5s ease-in-out infinite',
        shake: 'shake 0.4s ease-out both'
      }
    }
  },
  plugins: []
}
