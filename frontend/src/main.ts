import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import '@arco-design/web-vue/dist/arco.css'
import './styles/tokens.css'
import './styles/app.css'
import { router } from './router'
import App from './App.vue'
import { disposeTheme, initializeTheme } from './core/theme'

initializeTheme()
const app = createApp(App).use(createPinia()).use(router).use(ArcoVue)
app.onUnmount(disposeTheme)
app.mount('#app')
