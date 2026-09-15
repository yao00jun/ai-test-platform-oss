<script setup lang="ts">
import zhCN from '@arco-design/web-vue/es/locale/lang/zh-cn'
import AppShell from './layouts/AppShell.vue'
import SessionGate from './components/auth/SessionGate.vue'
</script>

<template>
  <a-config-provider :locale="zhCN">
    <SessionGate><AppShell /></SessionGate>
  </a-config-provider>
</template>
