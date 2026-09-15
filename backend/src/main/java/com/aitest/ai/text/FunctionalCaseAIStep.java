package com.aitest.ai.text;

/** Adapter for the upstream Markdown parser; persisted steps receive independent platform IDs. */
public class FunctionalCaseAIStep {
    private String id;
    private int num;
    private String desc;
    private String result;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getNum() { return num; }
    public void setNum(int num) { this.num = num; }
    public String getDesc() { return desc; }
    public void setDesc(String desc) { this.desc = desc; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
