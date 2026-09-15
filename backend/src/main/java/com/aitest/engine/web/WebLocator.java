package com.aitest.engine.web;

import com.aitest.common.Problem;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.util.Locale;

/** A portable locator notation shared by the editor, importer and Java export. */
public record WebLocator(String kind, String value, String name) {
    public static WebLocator parse(String source) {
        if (source == null || source.isBlank() || source.length() > 4096) throw Problem.invalid("定位器不能为空且最多 4096 字符");
        if (source.startsWith("role=")) {
            String role = source.substring(5), name = null;
            int bracket = role.indexOf("[name=");
            if (bracket >= 0) {
                if (!role.endsWith("]")) throw Problem.invalid("角色定位器格式为 role=button[name=名称]");
                name = unquote(role.substring(bracket + 6, role.length() - 1)); role = role.substring(0, bracket);
            }
            try { AriaRole.valueOf(role.replace("-", "").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw Problem.invalid("未知的 ARIA role: " + role); }
            return new WebLocator("role", role, name);
        }
        for (String kind : new String[]{"label", "text", "placeholder", "testId", "title", "alt"}) {
            if (source.startsWith(kind + "=")) return new WebLocator(kind, unquote(source.substring(kind.length() + 1)), null);
        }
        return new WebLocator("selector", source, null);
    }
    private static String unquote(String text) {
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'")))) return text.substring(1, text.length() - 1);
        return text;
    }
    public Locator locate(Page page, String frame) {
        return locate(page, frame, true);
    }
    public Locator locate(Page page, String frame, boolean exactMatch) {
        if (frame != null && !frame.isBlank()) return locate(page.frameLocator(frame), exactMatch);
        return switch (kind) {
            case "role" -> page.getByRole(role(), new Page.GetByRoleOptions().setName(name).setExact(exactMatch));
            case "label" -> page.getByLabel(value, new Page.GetByLabelOptions().setExact(exactMatch));
            case "text" -> page.getByText(value, new Page.GetByTextOptions().setExact(exactMatch));
            case "placeholder" -> page.getByPlaceholder(value, new Page.GetByPlaceholderOptions().setExact(exactMatch));
            case "testId" -> page.getByTestId(value);
            case "title" -> page.getByTitle(value, new Page.GetByTitleOptions().setExact(exactMatch));
            case "alt" -> page.getByAltText(value, new Page.GetByAltTextOptions().setExact(exactMatch));
            default -> page.locator(value);
        };
    }
    private Locator locate(FrameLocator frame, boolean exactMatch) {
        return switch (kind) {
            case "role" -> frame.getByRole(role(), new FrameLocator.GetByRoleOptions().setName(name).setExact(exactMatch));
            case "label" -> frame.getByLabel(value, new FrameLocator.GetByLabelOptions().setExact(exactMatch));
            case "text" -> frame.getByText(value, new FrameLocator.GetByTextOptions().setExact(exactMatch));
            case "placeholder" -> frame.getByPlaceholder(value, new FrameLocator.GetByPlaceholderOptions().setExact(exactMatch));
            case "testId" -> frame.getByTestId(value);
            case "title" -> frame.getByTitle(value, new FrameLocator.GetByTitleOptions().setExact(exactMatch));
            case "alt" -> frame.getByAltText(value, new FrameLocator.GetByAltTextOptions().setExact(exactMatch));
            default -> frame.locator(value);
        };
    }
    private AriaRole role() { return AriaRole.valueOf(value.replace("-", "").toUpperCase(Locale.ROOT)); }
}
