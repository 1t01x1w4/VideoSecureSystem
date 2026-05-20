import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/dashboard',
    },
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue'),
    },
    {
      path: '/',
      component: MainLayout,
      meta: { requiresAuth: true },
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/Dashboard.vue'),
        },
        {
          path: 'upload',
          name: 'Upload',
          component: () => import('@/views/Upload.vue'),
        },
        {
          path: 'player/:videoId',
          name: 'Player',
          component: () => import('@/views/Player.vue'),
        },
        {
          path: 'audit',
          name: 'AuditLogs',
          component: () => import('@/views/AuditLogs.vue'),
        },
      ],
    },
  ],
})

router.beforeEach((to, _from, next) => {
  const loggedIn = localStorage.getItem('loggedIn') === 'true'
  if (to.matched.some((r) => r.meta.requiresAuth) && !loggedIn) {
    next('/login')
  } else if (to.path === '/login' && loggedIn) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router
