package plugin.workflow.expressions;

import java.util.function.Function;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class UnaryOperator {
    private final String name;
    private final String sign;
    private final Function<Object, Object> function;
}
