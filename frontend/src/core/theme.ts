import { readonly, shallowRef } from 'vue'

export type Theme = 'light' | 'dark'
export type ThemePreference = Theme | null
export const THEME_STORAGE_KEY = 'ai-test-platform:theme'

function parsePreference(value: string | null): ThemePreference {
  return value === 'light' || value === 'dark' ? value : null
}

/** Owns page-level listeners; changing color never changes a workspace or editor identity. */
export function createThemeController(browser: Window) {
  const theme = shallowRef<Theme>('light')
  const preference = shallowRef<ThemePreference>(null)
  let storage: Storage | undefined
  let media: MediaQueryList | undefined
  try { storage = browser.localStorage; preference.value = parsePreference(storage.getItem(THEME_STORAGE_KEY)) }
  catch { /* Private or restricted storage still permits an in-memory preference. */ }
  try { media = browser.matchMedia('(prefers-color-scheme: dark)') }
  catch { /* Without a system signal, use light until the user chooses. */ }

  function apply() {
    const value = preference.value ?? (media?.matches ? 'dark' : 'light')
    theme.value = value
    const document = browser.document
    document.documentElement.dataset.theme = value
    document.documentElement.style.colorScheme = value
    document.body?.setAttribute('arco-theme', value)
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', value === 'dark' ? '#111722' : '#f6f8fb')
  }
  function setPreference(value: ThemePreference) {
    preference.value = value
    apply()
    try {
      if (value === null) storage?.removeItem(THEME_STORAGE_KEY)
      else storage?.setItem(THEME_STORAGE_KEY, value)
    } catch { /* Keep this session's choice even if persistence is unavailable. */ }
  }
  function onSystemChange() { if (preference.value === null) apply() }
  function onStorage(event: StorageEvent) {
    if (!storage || event.storageArea !== storage || (event.key !== THEME_STORAGE_KEY && event.key !== null)) return
    // Read the latest value: a queued event must not undo a newer choice made in this tab.
    try { preference.value = parsePreference(storage.getItem(THEME_STORAGE_KEY)) }
    catch { preference.value = parsePreference(event.newValue) }
    apply()
  }
  apply()
  media?.addEventListener('change', onSystemChange)
  browser.addEventListener('storage', onStorage)
  return {
    theme: readonly(theme),
    preference: readonly(preference),
    setPreference,
    toggle() { setPreference(theme.value === 'dark' ? 'light' : 'dark') },
    dispose() {
      media?.removeEventListener('change', onSystemChange)
      browser.removeEventListener('storage', onStorage)
    },
  }
}

let activeTheme: ReturnType<typeof createThemeController> | undefined
export function initializeTheme() { return activeTheme ??= createThemeController(window) }
export function useTheme() { return initializeTheme() }
export function disposeTheme() { activeTheme?.dispose(); activeTheme = undefined }
if (import.meta.hot) import.meta.hot.dispose(disposeTheme)
