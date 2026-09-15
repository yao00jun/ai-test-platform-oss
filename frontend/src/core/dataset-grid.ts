export interface Dataset {
  columns: string[]
  rows: Record<string, unknown>[]
}
export type CellKind = 'string' | 'number' | 'boolean' | 'json' | 'null' | 'missing'
export interface CellEditor { row: number; column: string; kind: CellKind; text: string }
export type DatasetEdit =
  | ({ type: 'cell' } & CellEditor)
  | { type: 'add-row' }
  | { type: 'remove-row'; row: number }
  | { type: 'move-row'; row: number; to: number }
  | { type: 'add-column'; name: string }
  | { type: 'rename-column'; column: string; name: string }
  | { type: 'remove-column'; column: string }
  | { type: 'move-column'; column: string; to: number }

function parseJson(text: string): unknown {
  return JSON.parse(text, (_key: string, value: unknown) => {
    if (typeof value === 'number' && !Number.isFinite(value)) throw new Error('JSON 数字超出支持范围。')
    return value
  })
}

export function readDataset(columnsText: string, rowsText: string): Dataset {
  let columns: unknown, rows: unknown
  try { columns = parseJson(columnsText || '[]'); rows = parseJson(rowsText || '[]') }
  catch { throw new Error('数据列和数据行必须是有效的 JSON 数组。') }
  if (!Array.isArray(columns) || columns.some(column => typeof column !== 'string' || !column.trim()) || new Set(columns).size !== columns.length) {
    throw new Error('数据列必须为列名数组，每个列名非空且唯一。')
  }
  if (!Array.isArray(rows)) throw new Error('数据行必须为 JSON 对象数组。')
  if (columns.length > 200 || rows.length > 100_000) throw new Error('数据集最多支持 200 列、100000 行。')
  const allowed = new Set(columns)
  rows.forEach((row, index) => {
    if (!row || typeof row !== 'object' || Array.isArray(row) || Object.keys(row).some(key => !allowed.has(key))) {
      throw new Error(`第 ${index + 1} 个数据行必须为对象，且只能包含已声明的数据列。`)
    }
  })
  return { columns, rows }
}

function rowAt(dataset: Dataset, index: number) {
  if (!Number.isInteger(index) || index < 0 || index >= dataset.rows.length) throw new Error('数据行不存在，请重新选择。')
  return dataset.rows[index]!
}
function columnAt(dataset: Dataset, name: string) {
  const index = dataset.columns.indexOf(name)
  if (index < 0) throw new Error('数据列不存在，请重新选择。')
  return index
}
function newColumn(dataset: Dataset, name: string, existing?: string) {
  if (!name.trim()) throw new Error('数据列名称必须非空。')
  if (name !== existing && dataset.columns.includes(name)) throw new Error('数据列名称必须唯一。')
}
function move<T>(values: T[], from: number, to: number) {
  if (!Number.isInteger(to) || to < 0 || to >= values.length) throw new Error('移动位置超出范围。')
  const next = [...values]
  next.splice(to, 0, ...next.splice(from, 1))
  return next
}

export function cellEditor(dataset: Dataset, row: number, column: string): CellEditor {
  columnAt(dataset, column)
  const record = rowAt(dataset, row)
  const value = record[column]
  const kind: CellKind = !Object.hasOwn(record, column) ? 'missing' : value === null ? 'null'
    : typeof value === 'string' ? 'string' : typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'boolean' : 'json'
  const text = kind === 'missing' || kind === 'null' ? '' : kind === 'json' ? JSON.stringify(value, null, 2) : String(value)
  return { row, column, kind, text }
}

function cellValue(kind: CellKind, text: string): unknown {
  switch (kind) {
    case 'string': return text
    case 'number': {
      const literal = text.trim()
      if (!/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(literal) || !Number.isFinite(Number(literal))) throw new Error('请输入有效数字，例如 2.5 或 -10。')
      return Number(literal)
    }
    case 'boolean':
      if (text.trim() !== 'true' && text.trim() !== 'false') throw new Error('布尔值只能填写 true 或 false。')
      return text.trim() === 'true'
    case 'json':
      try { return parseJson(text) }
      catch { throw new Error('单元格内容不是有效的 JSON。') }
    case 'null': return null
    case 'missing': return undefined
  }
}

/** Immutable edits let a rejected cell or structural operation leave the whole draft intact. */
export function applyDatasetEdit(dataset: Dataset, edit: DatasetEdit): Dataset {
  switch (edit.type) {
    case 'cell': {
      columnAt(dataset, edit.column)
      const row = { ...rowAt(dataset, edit.row) }
      const value = cellValue(edit.kind, edit.text)
      if (edit.kind === 'missing') delete row[edit.column]
      else Object.defineProperty(row, edit.column, { value, enumerable: true, configurable: true, writable: true })
      const rows = [...dataset.rows]
      rows[edit.row] = row
      return { columns: dataset.columns, rows }
    }
    case 'add-row':
      if (dataset.rows.length >= 100_000) throw new Error('数据集最多支持 100000 行。')
      return { columns: dataset.columns, rows: [...dataset.rows, {}] }
    case 'remove-row':
      rowAt(dataset, edit.row)
      return { columns: dataset.columns, rows: dataset.rows.filter((_, index) => index !== edit.row) }
    case 'move-row':
      rowAt(dataset, edit.row)
      return { columns: dataset.columns, rows: move(dataset.rows, edit.row, edit.to) }
    case 'add-column':
      newColumn(dataset, edit.name)
      if (dataset.columns.length >= 200) throw new Error('数据集最多支持 200 列。')
      return { columns: [...dataset.columns, edit.name], rows: dataset.rows }
    case 'rename-column': {
      columnAt(dataset, edit.column)
      newColumn(dataset, edit.name, edit.column)
      return {
        columns: dataset.columns.map(column => column === edit.column ? edit.name : column),
        rows: dataset.rows.map(row => Object.fromEntries(Object.entries(row).map(([key, value]) => [key === edit.column ? edit.name : key, value]))),
      }
    }
    case 'remove-column':
      columnAt(dataset, edit.column)
      return {
        columns: dataset.columns.filter(column => column !== edit.column),
        rows: dataset.rows.map(row => Object.fromEntries(Object.entries(row).filter(([key]) => key !== edit.column))),
      }
    case 'move-column':
      return { columns: move(dataset.columns, columnAt(dataset, edit.column), edit.to), rows: dataset.rows }
  }
}
