package graph.format;

import java.util.List;
import java.util.stream.Collectors;

public record ValidationResult(List<Diagnostic> diagnostics) {

    public ValidationResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public static ValidationResult pass() {
        return new ValidationResult(List.of());
    }

    public boolean ok() {
        return diagnostics.stream().noneMatch(Diagnostic::isError);
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).collect(Collectors.toList());
    }
}
