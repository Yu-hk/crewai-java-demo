package io.example.crew.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个 POJO 字段参与「结构化输出」契约。
 *
 * <p>对应 CrewAI {@code output_pydantic} 里 Pydantic 模型的 {@code Field(description=...)}：
 * 描述会被写进提示词，成为模型理解该字段含义的唯一依据。写不清楚，模型就会瞎填。
 *
 * <p>这里不引入任何校验框架——{@link Structure} 只做本项目需要的四件事：
 * 必填判定、类型检查、长度上限、枚举取值。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Field {

    /** 字段含义，会随 JSON Schema 一起注入提示词。 */
    String desc() default "";

    /** 是否允许缺省。缺省的字段在绑定后保持 Java 默认值。 */
    boolean optional() default false;

    /** 字符串最大长度（按字符计），0 表示不限制。 */
    int maxChars() default 0;
}
