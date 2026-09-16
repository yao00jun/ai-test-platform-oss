import { builtinEnvironments, type Environment } from 'vitest/runtime'

// Vue's custom renderer needs client render functions, but no DOM emulation or browser.
export default {
  ...builtinEnvironments.node,
  name: 'vue-components',
  viteEnvironment: 'client',
} satisfies Environment
