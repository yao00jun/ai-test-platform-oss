package com.aitest.ai.text;

import java.util.ArrayList;
import java.util.List;

/** Keeps the upstream parser contract while removing MeterSphere service dependencies. */
public class FunctionalCaseAiDTO {
    private String name;
    private String prerequisite;
    private String textDescription;
    private String expectedResult;
    private String description;
    private List<FunctionalCaseAIStep> steps = new ArrayList<>();
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getPrerequisite() { return prerequisite; }
    public void setPrerequisite(String value) { prerequisite = value; }
    public String getTextDescription() { return textDescription; }
    public void setTextDescription(String value) { textDescription = value; }
    public String getExpectedResult() { return expectedResult; }
    public void setExpectedResult(String value) { expectedResult = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public List<FunctionalCaseAIStep> getSteps() { return steps; }
    public void setSteps(List<FunctionalCaseAIStep> value) { steps = value; }
}
