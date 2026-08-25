package graph.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

import graph.types.TypeRef;

public record Overload(List<ParamDescriptor> params, TypeRef returnType) {

    public Overload {
        params = List.copyOf(params);
        Objects.requireNonNull(returnType, "returnType");
    }

    public static Overload of(TypeRef returnType, ParamDescriptor... params) {
        return new Overload(List.of(params), returnType);
    }

    public String signature() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params.get(i).type().print());
        }
        sb.append("):").append(returnType.print());
        return sb.toString();
    }

    public int arity() {
        return params.size();
    }

    public String hash() {
        return shortHash(signature());
    }

    private static String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Overload other)) {
            return false;
        }
        return params.equals(other.params) && returnType.equals(other.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(params, returnType);
    }

    @Override
    public String toString() {
        return signature();
    }
}
