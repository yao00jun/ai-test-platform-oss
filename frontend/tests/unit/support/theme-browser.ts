import { THEME_STORAGE_KEY } from '../../../src/core/theme'

export function themeBrowser(options: { dark?: boolean; stored?: string; readBlocked?: boolean; writeBlocked?: boolean; mediaUnavailable?: boolean } = {}) {
  const values = new Map<string, string>()
  if (options.stored !== undefined) values.set(THEME_STORAGE_KEY, options.stored)
  const storage = {
    getItem: (key: string) => { if (options.readBlocked) throw new Error('Storage unavailable'); return values.get(key) ?? null },
    setItem: (key: string, value: string) => { if (options.writeBlocked) throw new Error('Storage full'); values.set(key, value) },
    removeItem: (key: string) => { if (options.writeBlocked) throw new Error('Storage full'); values.delete(key) },
  }
  const media = Object.assign(new EventTarget(), { matches: options.dark ?? false })
  const root = { dataset: {} as Record<string, string>, style: { colorScheme: '' } }
  const bodyAttributes = new Map<string, string>()
  const metaAttributes = new Map<string, string>()
  const document = {
    documentElement: root,
    body: { setAttribute: (key: string, value: string) => bodyAttributes.set(key, value) },
    querySelector: () => ({ setAttribute: (key: string, value: string) => metaAttributes.set(key, value) }),
  }
  const browser = Object.assign(new EventTarget(), {
    document,
    get localStorage() { return storage },
    matchMedia: () => { if (options.mediaUnavailable) throw new Error('Media unavailable'); return media },
  })
  return {
    browser: browser as unknown as Window, root, storage, bodyAttributes, metaAttributes,
    system(dark: boolean) { media.matches = dark; media.dispatchEvent(Object.assign(new Event('change'), { matches: dark })) },
    remote(value: string | null, key: string | null = THEME_STORAGE_KEY, storageArea: unknown = storage) {
      if (key === null) values.clear()
      else if (value === null) values.delete(key)
      else values.set(key, value)
      browser.dispatchEvent(Object.assign(new Event('storage'), { key, newValue: value, storageArea }))
    },
  }
}
