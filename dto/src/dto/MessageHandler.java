package dto;

import java.util.function.Function;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MessageHandler<T, R> {
    private final Class<T> clazz;
    private final Function<T, R> fn;
}
