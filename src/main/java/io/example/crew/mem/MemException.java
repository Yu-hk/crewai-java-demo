package io.example.crew.mem;

/** 记忆层异常。与 LLM 异常分开，便于上层区分"模型挂了"和"记忆坏了"。 */
public class MemException extends RuntimeException {

    public MemException(String message) {
        super(message);
    }

    public MemException(String message, Throwable cause) {
        super(message, cause);
    }
}
