package com.aitest.engine.web;

import com.aitest.execution.Values;
import com.microsoft.playwright.*;
import java.util.*;

/** Fixed DOM read code, never model-supplied JavaScript. Form values and HTML source are excluded. */
final class WebPageObservation {
    private static final String READ = """
        () => {
          const candidates = [...document.querySelectorAll('input,button,select,textarea,a,[role],[data-testid],[id],h1,h2,h3,p')];
          const path = el => {
            if (el.id) return '#' + CSS.escape(el.id);
            const parts = [];
            for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
              if (node.id) { parts.unshift('#' + CSS.escape(node.id)); break; }
              const siblings = node.parentElement ? [...node.parentElement.children].filter(s => s.tagName === node.tagName) : [node];
              parts.unshift(node.tagName.toLowerCase() + ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')');
              if (node.tagName === 'BODY') break;
            }
            return parts.join(' > ');
          };
          const elements = candidates.slice(0, 250).map(el => {
            const selectors = [path(el)];
            const testId = el.getAttribute('data-testid');
            const label = el.getAttribute('aria-label') || (el.labels && el.labels[0] && el.labels[0].innerText);
            const placeholder = el.getAttribute('placeholder');
            if (testId) selectors.push('testId=' + testId);
            if (label && label.length < 200) selectors.push('label=' + label.trim());
            if (placeholder && placeholder.length < 200) selectors.push('placeholder=' + placeholder);
            return {selector: selectors[0], selectors, tag: el.tagName.toLowerCase(),
              role: el.getAttribute('role') || '', type: el.getAttribute('type') || '',
              text: (el.innerText || '').trim().slice(0, 300), visible: !!el.getClientRects().length,
              disabled: !!el.disabled};
          });
          const text = document.body ? document.body.innerText : '';
          return {title: document.title, url: location.href, text: text.slice(0, 16000),
            elements, truncated: candidates.length > 250 || text.length > 16000};
        }
        """;
    static Map<String, Object> capture(Page page) {
        Map<String, Object> result = new LinkedHashMap<>(Values.map(page.evaluate(READ)));
        List<Map<String, Object>> frames = new ArrayList<>();
        for (Frame frame : page.frames()) {
            if (frame == page.mainFrame() || frames.size() == 20) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            try {
                item.putAll(Values.map(frame.evaluate(READ)));
                var element = frame.frameElement(); String id = element.getAttribute("id");
                item.put("frame", id == null || id.isBlank() ? "" : "[id=\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]");
                if (frame.parentFrame() != page.mainFrame()) item.put("unavailableReason", "嵌套 Frame 需要提供录制证据确定完整路径");
            } catch (RuntimeException unavailable) { item.put("unavailableReason", "Frame 暂时无法读取"); }
            frames.add(item);
        }
        result.put("frames", frames); result.put("capturedAt", java.time.Instant.now().toString()); return result;
    }
    private WebPageObservation() { }
}
