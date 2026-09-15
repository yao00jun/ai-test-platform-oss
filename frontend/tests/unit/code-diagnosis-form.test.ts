import { describe, expect, it } from 'vitest'
import { codeDiagnosisDraft, compileCodeDiagnosis, rebaseCodeDiagnosisDraft } from '../../src/core/code-diagnosis-form'

describe('manual code diagnosis', () => {
  it('keeps an untouched legacy diagnosis empty and treats missing confidence/regression as unknown', () => {
    const empty = codeDiagnosisDraft({})
    expect(compileCodeDiagnosis(empty)).toEqual({})
    expect(compileCodeDiagnosis({ ...empty, root_cause: '尚待验证的人工推测' })).toEqual({
      formatVersion: 'aitest.code-rca/v1', root_cause: '尚待验证的人工推测', affected_code_path: '', suggested_fix: '', is_regression: null, confidence: null,
    })
  })
  it('preserves patch bytes and zero confidence while rejecting invalid numeric input', () => {
    const draft = codeDiagnosisDraft({ root_cause: '待核实', suggested_fix: '--- a/Order.java\n+++ b/Order.java\n@@ -1 +1 @@\n-old\n+new\n', confidence: 0, is_regression: false })
    expect(compileCodeDiagnosis(draft)).toMatchObject({ confidence: 0, is_regression: false, suggested_fix: '--- a/Order.java\n+++ b/Order.java\n@@ -1 +1 @@\n-old\n+new\n' })
    for (const confidence of ['NaN', 'Infinity', '-0.1', '1.1', 'abc']) expect(() => compileCodeDiagnosis({ ...draft, confidence })).toThrow()
  })
  it('explicit rebase carries only edited fields and keeps the latest unrelated fix and regression judgement', () => {
    const baseline = codeDiagnosisDraft({ root_cause: '旧结论', suggested_fix: '旧补丁', confidence: 0.4 })
    const mine = { ...baseline, root_cause: '人工未保存结论', confidence: '' }
    const latest = codeDiagnosisDraft({ root_cause: '另一结论', suggested_fix: '新的服务端补丁', confidence: 0.9, is_regression: true })
    expect(compileCodeDiagnosis(rebaseCodeDiagnosisDraft(baseline, mine, latest))).toMatchObject({ root_cause: '人工未保存结论', suggested_fix: '新的服务端补丁', confidence: null, is_regression: true })
  })
})
