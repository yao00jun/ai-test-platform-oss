// Adapted from MeterSphere; Copyright (c) 2026-present FIT2CLOUD. GPLv3 with upstream additional terms.
// Original author: song-cc-rock. See docs/third-party-reuse.md and licenses/MeterSphere-LICENSE.
package com.aitest.ai.text;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.tables.*;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.aitest.common.Problem;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author song-cc-rock
 */
public class MdUtil {

	public static final String MD_START_TAG = "featureCaseStart";
	public static final String MD_END_TAG = "featureCaseEnd";

	/**
	 * Markdown内容转换为Case对象
	 *
	 * @param content Markdown内容
	 * @return Case对象
	 */
	public static FunctionalCaseAiDTO transformToCaseDTO(String content) {
		try {
			MutableDataSet options = new MutableDataSet();
			options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
			Parser parser = Parser.builder(options).build();
			Node document = parser.parse(content);
			FunctionalCaseAiDTO caseDTO = new FunctionalCaseAiDTO();
			String currentSection = "";
			for (Node node : document.getChildren()) {
				if (node instanceof Heading) {
					int level = ((Heading) node).getLevel();
					String headText = ((Heading) node).getText().toString();
					if (level == 2) {
						caseDTO.setName(headText.trim());
					} else if (level == 3) {
						currentSection = headText.trim();
					}
				} else {
					if (currentSection.contains("步骤")) {
						List<FunctionalCaseAIStep> steps = new ArrayList<>(caseDTO.getSteps());
						stepNodeTableToObj(node, steps);
						caseDTO.setSteps(steps);
					} else if (currentSection.contains("前置条件") || currentSection.contains("前提条件")) {
						caseDTO.setPrerequisite(appendHtml(caseDTO.getPrerequisite(), toHtml(node)));
					} else if (currentSection.contains("文本描述")) {
						caseDTO.setTextDescription(appendHtml(caseDTO.getTextDescription(), toHtml(node)));
					} else if (currentSection.contains("预期结果")) {
						caseDTO.setExpectedResult(appendHtml(caseDTO.getExpectedResult(), toHtml(node)));
					} else if (currentSection.contains("备注") || currentSection.contains("描述")) {
						caseDTO.setDescription(appendHtml(caseDTO.getDescription(), toHtml(node)));
					}
				}
			}

			if (StringUtils.isBlank(caseDTO.getName())) throw Problem.invalid("Markdown 用例缺少二级标题");
			if (caseDTO.getSteps().isEmpty() && StringUtils.isBlank(caseDTO.getTextDescription())) throw Problem.invalid("Markdown 用例缺少步骤表格");
			return caseDTO;
		} catch (Exception e) {
			throw Problem.invalid(e.getMessage());
		}
	}

	/**
	 * 批量转换Markdown内容为Case对象列表
	 *
	 * @param content Markdown内容
	 * @return Case对象列表
	 */
	public static List<FunctionalCaseAiDTO> batchTransformToCaseDTO(String content) {
		List<FunctionalCaseAiDTO> aiCases = new ArrayList<>();
		StringBuilder block = null;
		for (String line : content.lines().toList()) {
			String trimmed = line.strip();
			if (trimmed.equals(MD_START_TAG)) {
				if (block != null) throw Problem.invalid("用例定界符嵌套");
				block = new StringBuilder();
			} else if (trimmed.equals(MD_END_TAG)) {
				if (block == null) throw Problem.invalid("用例结束标记没有对应开始标记");
				aiCases.add(transformToCaseDTO(TextCleaner.cleanMdTitle(block.toString())));
				block = null;
			} else if (block != null) block.append(line).append('\n');
		}
		if (block != null) throw Problem.invalid("用例输出被截断，缺少 featureCaseEnd");
		if (aiCases.isEmpty()) throw Problem.invalid("未找到完整的 featureCaseStart/featureCaseEnd 用例块");
		return aiCases;
	}

	/**
	 * Markdown步骤描述表格节点转换为步骤对象
	 *
	 * @param node     Markdown节点
	 * @param steps    用例步骤列表
	 */
	private static void stepNodeTableToObj(Node node, List<FunctionalCaseAIStep> steps) {
		if (node instanceof TableBlock) {
			for (Node tableNode : node.getChildren()) {
				if (tableNode instanceof TableHead) {
					continue;
				}
				if (tableNode instanceof TableBody) {
					int index = 0;
					for (Node rowNode : tableNode.getChildren()) {
						if (rowNode instanceof TableRow) {
							List<String> cells = new ArrayList<>();
							for (Node cell : rowNode.getChildren()) {
								if (cell instanceof TableCell) {
									cells.add(((TableCell) cell).getText().toString().replaceAll("(?i)<br\\s*/?>", "\n"));
								}
							}
							if (cells.size() >= 2) {
								FunctionalCaseAIStep step = new FunctionalCaseAIStep();
                                step.setId(UUID.randomUUID().toString());
								step.setNum(index);
								step.setDesc(cells.get(0));
								step.setResult(cells.get(1));
								steps.add(step);
								index++;
							}
						}
					}
				}
			}
		}
	}

	/**
	 * 将Markdown节点转换为HTML字符串
	 * @param node 节点
	 * @return HTML字符串
	 */
	private static String toHtml(Node node) {
		MutableDataSet options = new MutableDataSet();
		options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
		Parser parser = Parser.builder(options).build();
		HtmlRenderer renderer = HtmlRenderer.builder(options).escapeHtml(true).build();
		BasedSequence fragment = node.getChars();
		Node parsedFragment = parser.parse(fragment.toString());
		return renderer.render(parsedFragment);
	}

	/**
	 * 追加html
	 *
	 * @param original 原有字符串
	 * @param addition 追加的字符串
	 * @return 合并后的字符串
	 */
	private static String appendHtml(String original, String addition) {
		if (original == null) {
			return addition;
		}
		return original + addition;
	}
}
