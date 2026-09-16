import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createRenderer, h, nextTick, ref, type App } from 'vue'
import { disposeTheme, useTheme } from '../../src/core/theme'
import { themeBrowser } from './support/theme-browser'
import MonacoEditor from '../../src/components/editors/MonacoEditor.vue'

// Monaco needs workers, layout and a canvas. Keep Vue and our theme controller real;
// replace only that browser boundary, including the destructive effect of setValue.
const monacoState = vi.hoisted(() => ({
  theme: '',
  themes: new Map<string, { base: string }>(),
  instances: [] as Array<{
    value: string; undo: string[]; selection: number; disposed: boolean; model: { disposed: boolean; dispose(): void };
    change?: () => void; type(value: string): void; setValue(value: string): void; getValue(): string;
    getModel(): { disposed: boolean; dispose(): void }; dispose(): void;
    onDidChangeModelContent(listener: () => void): { dispose(): void };
  }>,
}))
vi.mock('monaco-editor/editor/editor.api', () => ({ editor: {
  defineTheme(name: string, theme: { base: string }) { monacoState.themes.set(name, theme) },
  setTheme(name: string) { monacoState.theme = name },
  create(_host: unknown, options: { value: string }) {
    const instance: typeof monacoState.instances[number] = {
      value: options.value, undo: [], selection: 0, disposed: false,
      model: { disposed: false, dispose() { this.disposed = true } },
      getValue() { return this.value },
      setValue(value) { this.value = value; this.undo = []; this.selection = 0; this.change?.() },
      type(value) { this.undo.push(this.value); this.value = value; this.selection = value.length; this.change?.() },
      getModel() { return this.model },
      dispose() { this.disposed = true },
      onDidChangeModelContent(listener) { this.change = listener; return { dispose: () => { this.change = undefined } } },
    }
    monacoState.instances.push(instance)
    return instance
  },
} }))
vi.mock('monaco-editor/languages/definitions/sql/register', () => ({}))
vi.mock('monaco-editor/languages/definitions/javascript/register', () => ({}))
vi.mock('monaco-editor/language/json/monaco.contribution', () => ({}))
vi.mock('monaco-editor/editor/editor.worker?worker', () => ({ default: class {} }))
vi.mock('monaco-editor/language/json/json.worker?worker', () => ({ default: class {} }))

interface HostNode { children: HostNode[]; parent: HostNode | null }
const host = (): HostNode => ({ children: [], parent: null })
const renderer = createRenderer<HostNode, HostNode>({
  createElement: host, createText: host, createComment: host,
  insert(node, parent, anchor) {
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove(node) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1); node.parent = null },
  parentNode: node => node.parent,
  nextSibling(node) { return node.parent?.children[(node.parent.children.indexOf(node)) + 1] ?? null },
  patchProp() {}, setText() {}, setElementText() {},
})
let app: App | undefined
beforeEach(() => {
  monacoState.theme = ''
  monacoState.instances = []
  vi.stubGlobal('self', {})
})
afterEach(() => { app?.unmount(); app = undefined; disposeTheme(); vi.unstubAllGlobals() })

it('uses dark immediately for a new editor and preserves drafts, undo, selection and models while switching', async () => {
  vi.stubGlobal('window', themeBrowser({ stored: 'dark' }).browser)
  const theme = useTheme()
  const draft = ref('{"saved": true}')
  app = renderer.createApp({ setup: () => () => h(MonacoEditor, {
    modelValue: draft.value, language: 'json', label: '请求体', 'onUpdate:modelValue': value => { draft.value = value },
  }) })
  app.mount(host())
  expect(monacoState.theme).toBe('ai-test-dark')
  expect(monacoState.themes.get(monacoState.theme)?.base).toBe('vs-dark')
  const editor = monacoState.instances[0]!
  const model = editor.getModel()
  editor.type('{"unsaved": "草稿"}')
  await nextTick()
  const selection = editor.selection
  theme.setPreference('light')
  await nextTick()
  expect(monacoState.theme).toBe('ai-test-light')
  expect(monacoState.instances).toHaveLength(1)
  expect(editor.value).toBe('{"unsaved": "草稿"}')
  expect(draft.value).toBe('{"unsaved": "草稿"}')
  expect(editor.undo).toEqual(['{"saved": true}'])
  expect(editor.selection).toBe(selection)
  expect(editor.getModel()).toBe(model)
  expect(editor.disposed).toBe(false)
})

it('keeps global theme updates after the first of two editors closes, then releases the final subscription', async () => {
  vi.stubGlobal('window', themeBrowser().browser)
  const theme = useTheme()
  const showFirst = ref(true)
  const showSecond = ref(true)
  app = renderer.createApp({ setup: () => () => h('div', [
    showFirst.value ? h(MonacoEditor, { key: 'first', modelValue: 'first', language: 'json', label: '列名' }) : null,
    showSecond.value ? h(MonacoEditor, { key: 'second', modelValue: 'second', language: 'json', label: '数据行' }) : null,
  ]) })
  app.mount(host())
  showFirst.value = false
  await nextTick()
  expect(monacoState.instances[0]!.disposed).toBe(true)
  expect(monacoState.instances[0]!.model.disposed).toBe(true)
  theme.setPreference('dark')
  await nextTick()
  expect(monacoState.theme).toBe('ai-test-dark')
  expect(monacoState.instances).toHaveLength(2)
  expect(monacoState.instances[1]!.disposed).toBe(false)
  showSecond.value = false
  await nextTick()
  expect(monacoState.instances[1]!.model.disposed).toBe(true)
  theme.setPreference('light')
  await nextTick()
  expect(monacoState.theme).toBe('ai-test-dark')
})
