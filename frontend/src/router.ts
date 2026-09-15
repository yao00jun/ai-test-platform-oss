import { createRouter, createWebHistory } from 'vue-router'

export const navigation = [
  { path: '/', name: 'workspace', label: '工作台', icon: 'apps' },
  { path: '/projects', name: 'projects', label: '项目管理', icon: 'folder' },
  { path: '/cases', name: 'cases', label: '测试用例', icon: 'check' },
  { path: '/api-tests', name: 'api-tests', label: '接口测试', icon: 'code' },
  { path: '/scenarios', name: 'scenarios', label: '场景自动化', icon: 'branch' },
  { path: '/ui-tests', name: 'ui-tests', label: 'Playwright UI', icon: 'desktop' },
  { path: '/plans', name: 'plans', label: '测试计划', icon: 'calendar' },
  { path: '/bugs', name: 'bugs', label: '缺陷管理', icon: 'bug' },
] as const

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'workspace', component: () => import('./pages/DashboardPage.vue') },
    { path: '/projects', name: 'projects', component: () => import('./pages/ProjectsPage.vue') },
    { path: '/cases', name: 'cases', component: () => import('./pages/CasesPage.vue') },
    { path: '/api-tests', name: 'api-tests', component: () => import('./pages/ApiTestsPage.vue') },
    { path: '/scenarios', name: 'scenarios', component: () => import('./pages/ScenariosPage.vue') },
    { path: '/ui-tests', name: 'ui-tests', component: () => import('./pages/UiTestsPage.vue') },
    { path: '/plans', name: 'plans', component: () => import('./pages/PlansPage.vue') },
    { path: '/bugs', name: 'bugs', component: () => import('./pages/BugsPage.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.afterEach((to) => { document.title = `${navigation.find((item) => item.name === to.name)?.label ?? '工作台'} · AI 测试平台` })
