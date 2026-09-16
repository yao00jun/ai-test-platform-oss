import { effectScope, watch, type EffectScope } from 'vue'
import * as monaco from 'monaco-editor/editor/editor.api'
import { useTheme } from './theme'

// Match tokens.css surfaces while inheriting Monaco's contrasting syntax colors.
const palettes = {
  light: {
    surface: '#ffffff', raised: '#ffffff', subtle: '#f8f9fc', fill: '#f5f7fa', text: '#1e293b',
    muted: '#64748b', secondary: '#475569', border: '#e5eaf1', strong: '#bec8dd', primary: '#4f46e5',
    selection: '#e3e0ff', inactiveSelection: '#eeedff', danger: '#b4233e', warning: '#976414',
  },
  dark: {
    surface: '#192230', raised: '#202c3d', subtle: '#1e2a3b', fill: '#243145', text: '#e3eaf5',
    muted: '#a1aec1', secondary: '#bdc9da', border: '#334156', strong: '#53647b', primary: '#a59bff',
    selection: '#49436e', inactiveSelection: '#2d2949', danger: '#ff9eaf', warning: '#efbf70',
  },
}
let registered = false
let consumers = 0
let scope: EffectScope | undefined

/** Monaco's theme is global, so multiple editors share one detached subscription. */
export function connectMonacoTheme(): () => void {
  if (!registered) {
    for (const name of ['light', 'dark'] as const) {
      const color = palettes[name]
      monaco.editor.defineTheme(`ai-test-${name}`, {
        base: name === 'dark' ? 'vs-dark' : 'vs', inherit: true, rules: [],
        colors: {
          'editor.background': color.surface,
          'editor.foreground': color.text,
          'editorGutter.background': color.surface,
          'editorLineNumber.foreground': color.muted,
          'editorLineNumber.activeForeground': color.secondary,
          'editorCursor.foreground': color.primary,
          'editor.selectionBackground': color.selection,
          'editor.inactiveSelectionBackground': color.inactiveSelection,
          'editor.lineHighlightBackground': color.subtle,
          'editorIndentGuide.background1': color.border,
          'editorIndentGuide.activeBackground1': color.strong,
          'editorWhitespace.foreground': color.strong,
          'editorWidget.background': color.raised,
          'editorWidget.foreground': color.text,
          'editorWidget.border': color.border,
          'editorHoverWidget.background': color.raised,
          'editorHoverWidget.foreground': color.text,
          'editorHoverWidget.border': color.border,
          'editorSuggestWidget.background': color.raised,
          'editorSuggestWidget.foreground': color.text,
          'editorSuggestWidget.border': color.border,
          'editorSuggestWidget.selectedBackground': color.inactiveSelection,
          'input.background': color.fill,
          'input.foreground': color.text,
          'input.border': color.border,
          'focusBorder': color.primary,
          'editorError.foreground': color.danger,
          'editorWarning.foreground': color.warning,
        },
      })
    }
    registered = true
  }
  if (consumers++ === 0) {
    // A watcher owned by the first component would stop when only that editor closes.
    scope = effectScope(true)
    scope.run(() => watch(useTheme().theme, name => monaco.editor.setTheme(`ai-test-${name}`), { immediate: true, flush: 'sync' }))
  }
  let released = false
  return () => {
    if (released) return
    released = true
    if (--consumers === 0) { scope?.stop(); scope = undefined }
  }
}
