package io.example.crew.flow;

/** 编排图执行异常，带出错的节点名。 */
public class DagException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String node;

    public DagException(String message, String node, Throwable cause) {
        super(message, cause);
        this.node = node;
    }

    /** 出错的节点名。定位问题时先看这个，再去看 cause。 */
    public String node() {
        return node;
    }
}
