package plugin.workflow.expressions;

import java.util.function.BiFunction;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class BinaryOperator {
    private final String name;
    private final String sign;
    private final BiFunction<Object, Object, Object> function;
}
