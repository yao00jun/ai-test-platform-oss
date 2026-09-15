<script lang="ts">
import { computed, defineComponent, h, type VNodeChild } from 'vue'

const allowed = new Set(['p', 'br', 'strong', 'b', 'em', 'i', 'u', 's', 'del', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6'])
const blocked = new Set(['script', 'style', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'svg', 'math', 'video', 'audio', 'source', 'form', 'input', 'button', 'textarea', 'select', 'template'])
const blocks = new Set(['p', 'div', 'ul', 'ol', 'li', 'br', 'blockquote', 'pre', 'tr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6'])
const richMarkup = /<\/?(?:p|br|div|span|strong|b|em|i|u|s|del|ul|ol|li|table|blockquote|h[1-6]|code|pre)(?:\s|>|\/)/i

export default defineComponent({
  props: { value: { type: String, default: '' }, compact: Boolean },
  setup(props) {
    const nodes = computed<VNodeChild[]>(() => {
      if (!richMarkup.test(props.value)) return [props.value]
      // Template contents stay inert. Only fresh Vue nodes from this tag allowlist reach the page;
      // no source attributes, links, event handlers or resource URLs are ever copied.
      const template = document.createElement('template')
      template.innerHTML = props.value
      function visit(node: Node): VNodeChild[] {
        if (node.nodeType === Node.TEXT_NODE) return [node.textContent ?? '']
        if (!(node instanceof Element)) return []
        const tag = node.tagName.toLowerCase()
        if (blocked.has(tag)) return []
        if (tag === 'img') return [node.getAttribute('alt') ?? '']
        const children = Array.from(node.childNodes).flatMap(visit)
        if (props.compact) return blocks.has(tag) ? [...children, '\n'] : children
        return allowed.has(tag) ? [h(tag, {}, children)] : children
      }
      const children = Array.from(template.content.childNodes).flatMap(visit)
      return props.compact ? [children.join('').replace(/\n{3,}/g, '\n\n').trim()] : children
    })
    return () => h('div', { class: ['safe-rich-text', { compact: props.compact }] }, nodes.value)
  },
})
</script>

<style scoped>
.safe-rich-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; min-width: 0; }
.safe-rich-text :deep(p) { margin: 0 0 7px; }.safe-rich-text :deep(p:last-child) { margin-bottom: 0; }
.safe-rich-text :deep(ul), .safe-rich-text :deep(ol) { margin: 5px 0; padding-left: 22px; }
.safe-rich-text :deep(table) { display: block; max-width: 100%; overflow: auto; border-collapse: collapse; }
.safe-rich-text :deep(td), .safe-rich-text :deep(th) { border: 1px solid var(--border); padding: 5px 8px; }
.safe-rich-text :deep(pre) { overflow: auto; white-space: pre; padding: 10px; background: var(--color-fill-1); border-radius: 5px; }
.safe-rich-text :deep(blockquote) { margin: 8px 0; padding-left: 10px; border-left: 3px solid var(--border); }
.safe-rich-text.compact { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
