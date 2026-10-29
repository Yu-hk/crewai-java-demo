package io.example.crew.core;

/**
 * 流程执行异常。
 *
 * <p>带 {@code completedSteps}：中断时最需要知道的不是"失败了"，而是"失败在哪一步、
 * 前面几步的成果还在不在"。有这个数字，调用方才能判断是续跑还是重来。
 */
public class CrewException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int completedSteps;
    private final int totalSteps;

    public CrewException(String message, int completedSteps, int totalSteps, Throwable cause) {
        super(message, cause);
        this.completedSteps = completedSteps;
        this.totalSteps = totalSteps;
    }

    public int completedSteps() {
        return completedSteps;
    }

    public int totalSteps() {
        return totalSteps;
    }
}
