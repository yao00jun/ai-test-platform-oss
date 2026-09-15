package com.aitest.analysis.source;

/** A single budget is shared by every input (including Git baselines) of one snapshot. */
public final class SourceBudget {
    private final int maxFiles;
    private final long maxFileBytes, maxBytes;
    private int files;
    private long bytes;

    SourceBudget(int maxFiles, long maxFileBytes, long maxBytes) { this.maxFiles = maxFiles; this.maxFileBytes = maxFileBytes; this.maxBytes = maxBytes; }
    public long maxFileBytes() { return maxFileBytes; }
    public long remainingBytes() { return maxBytes - bytes; }
    public int remainingFiles() { return maxFiles - files; }
    public boolean fits(long size) { return remainingFiles() > 0 && size >= 0 && size <= maxFileBytes && size <= remainingBytes(); }
    public void include(long size) { if (!fits(size)) throw new IllegalStateException("Source budget exceeded"); files++; bytes += size; }
}
