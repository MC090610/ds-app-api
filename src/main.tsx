import { createRoot } from 'react-dom/client'
import { App } from './app/App.js'
import { setupTheme } from './app/theme.js'
import { ensureKatex } from './services/markdown/katex.js'
import { setupAndroidBridge } from './services/androidBridge.js'
import './app/theme.css'
import './app/global.css'

ensureKatex()
setupTheme()
setupAndroidBridge()

createRoot(document.getElementById('root') as HTMLElement).render(<App />)
