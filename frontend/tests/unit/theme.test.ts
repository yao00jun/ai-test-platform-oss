import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { describe, expect, it } from 'vitest'
import { createThemeController, THEME_STORAGE_KEY } from '../../src/core/theme'
import { themeBrowser } from './support/theme-browser'

describe('theme preferences', () => {
  it('uses the OS preference on first visit and follows later OS changes', () => {
    const env = themeBrowser({ dark: true })
    const controller = createThemeController(env.browser)
    expect(controller.theme.value).toBe('dark')
    expect(controller.preference.value).toBeNull()
    expect(env.root.dataset.theme).toBe('dark')
    expect(env.root.style.colorScheme).toBe('dark')
    expect(env.bodyAttributes.get('arco-theme')).toBe('dark')
    expect(env.metaAttributes.get('content')).toBe('#111722')
    expect(env.storage.getItem(THEME_STORAGE_KEY)).toBeNull()
    env.system(false)
    expect(controller.theme.value).toBe('light')
    expect(env.bodyAttributes.get('arco-theme')).toBe('light')
    controller.dispose()
  })

  it('persists an explicit choice across reloads and ignores OS changes until reset', () => {
    const env = themeBrowser({ dark: true })
    const controller = createThemeController(env.browser)
    controller.toggle()
    expect(env.storage.getItem(THEME_STORAGE_KEY)).toBe('light')
    env.system(false)
    env.system(true)
    expect(controller.theme.value).toBe('light')
    controller.dispose()
    const reloaded = createThemeController(env.browser)
    expect(reloaded.theme.value).toBe('light')
    reloaded.setPreference(null)
    expect(env.storage.getItem(THEME_STORAGE_KEY)).toBeNull()
    expect(reloaded.theme.value).toBe('dark')
    reloaded.dispose()
  })

  it.each(['invalid', 'DARK', 'null', ''])('ignores corrupt stored preference %j', stored => {
    const env = themeBrowser({ stored, dark: true })
    const controller = createThemeController(env.browser)
    expect(controller.theme.value).toBe('dark')
    expect(controller.preference.value).toBeNull()
    controller.dispose()
  })

  it('keeps a session choice usable when storage reads or writes are blocked', () => {
    const env = themeBrowser({ dark: true, readBlocked: true, writeBlocked: true })
    const controller = createThemeController(env.browser)
    expect(controller.theme.value).toBe('dark')
    expect(() => controller.toggle()).not.toThrow()
    expect(controller.theme.value).toBe('light')
    env.system(false)
    env.system(true)
    expect(controller.theme.value).toBe('light')
    expect(env.root.dataset.theme).toBe('light')
    controller.dispose()
  })

  it('still switches theme when accessing localStorage itself throws', () => {
    const env = themeBrowser({ dark: true })
    Object.defineProperty(env.browser, 'localStorage', { get() { throw new Error('SecurityError') } })
    const controller = createThemeController(env.browser)
    expect(controller.theme.value).toBe('dark')
    controller.setPreference('light')
    expect(controller.theme.value).toBe('light')
    controller.dispose()
  })

  it('synchronizes other tabs, including clearing the preference, without reacting to other storage', () => {
    const env = themeBrowser({ stored: 'light', dark: false })
    const controller = createThemeController(env.browser)
    env.remote('dark', 'unrelated')
    expect(controller.theme.value).toBe('light')
    env.remote('dark', THEME_STORAGE_KEY, {})
    expect(controller.theme.value).toBe('light')
    env.remote('dark')
    expect(controller.theme.value).toBe('dark')
    expect(controller.preference.value).toBe('dark')
    env.remote(null)
    expect(controller.theme.value).toBe('light')
    env.system(true)
    expect(controller.theme.value).toBe('dark')
    env.remote('light')
    env.remote(null, null)
    expect(controller.theme.value).toBe('dark')
    controller.dispose()
  })

  it('releases both event listeners when the app lifecycle ends', () => {
    const env = themeBrowser()
    const controller = createThemeController(env.browser)
    controller.dispose()
    env.system(true)
    env.remote('dark')
    expect(controller.theme.value).toBe('light')
    expect(env.root.dataset.theme).toBe('light')
  })

  it('defaults to light if media queries are unavailable, while retaining manual control', () => {
    const env = themeBrowser({ mediaUnavailable: true })
    const controller = createThemeController(env.browser)
    expect(controller.theme.value).toBe('light')
    controller.toggle()
    expect(controller.theme.value).toBe('dark')
    controller.dispose()
  })
})

describe('first paint', () => {
  const html = readFileSync(new URL('../../index.html', import.meta.url), 'utf8')
  const inlineScripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
    .filter(match => !match[0].includes('src='))

  it.each([
    { stored: undefined, dark: true, expected: 'dark' },
    { stored: 'light', dark: true, expected: 'light' },
    { stored: 'dark', dark: false, expected: 'dark' },
    { stored: 'broken', dark: true, expected: 'dark' },
    { stored: undefined, dark: true, readBlocked: true, expected: 'dark' },
    { stored: undefined, mediaUnavailable: true, expected: 'light' },
  ])('applies $expected before the app module loads for $stored / $dark', options => {
    const env = themeBrowser(options)
    const context = {
      window: env.browser, document: env.browser.document,
      localStorage: env.storage, matchMedia: env.browser.matchMedia,
    }
    for (const script of inlineScripts) runInNewContext(script[1]!, context)
    expect(env.root.dataset.theme).toBe(options.expected)
    expect(env.root.style.colorScheme).toBe(options.expected)
    const controller = createThemeController(env.browser)
    expect(controller.theme.value).toBe(options.expected)
    controller.dispose()
  })
})
