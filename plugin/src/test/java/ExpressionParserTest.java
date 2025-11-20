// import static org.junit.jupiter.api.Assertions.assertEquals;

// import java.util.HashMap;
// import java.util.Map;

// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;

// import plugin.workflow.expressions.ExpressionParser;

// public class ExpressionParserTest {
// ExpressionParser parser = new ExpressionParser();

// Map<String, Object> variables = new HashMap<>();

// @BeforeEach
// void setUp() {
// variables.clear();
// variables.put("a", 1d);
// variables.put("b", 2d);
// }

// @Test
// void testAddition() {
// assertEquals(parser.evaluate(Object.class, "1 + 2", variables), 3d);
// }

// @Test
// void testAdditionWithVariable() {
// assertEquals(parser.evaluate(Object.class, "{{a}} + {{ b}}", variables), 3d);
// }
// }
