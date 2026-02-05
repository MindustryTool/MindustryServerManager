package plugin.commands;

import java.util.function.Function;

import arc.struct.Seq;
import arc.util.Strings;
import lombok.Getter;
import lombok.Setter;

public abstract class PluginCommand {
    @Getter
    @Setter
    protected String name;

    @Getter
    @Setter
    protected String description;

    protected Seq<Param> params = new Seq<>();

    protected Param optional(String name) {
        Param p = new Param(name, ParamType.Optional, new Seq<>());
        params.add(p);
        return p;
    }

    protected Param required(String name) {
        Param p = new Param(name, ParamType.Required, new Seq<>());
        params.add(p);
        return p;
    }

    protected Param variadic(String name) {
        Param p = new Param(name, ParamType.Variadic, new Seq<>());
        params.add(p);
        return p;
    }

    public class ParamException extends IllegalArgumentException {
        public ParamException(Param param, String message) {
            super(message + " " + param.toParamText() + "=" + param.value);
        }
    }

    public class Param {
        private String name;
        private ParamType type;
        private String value;

        private Seq<Runnable> validators = new Seq<>();

        public static void parse(Seq<Param> params, String[] args) {
            for (int i = 0; i < params.size; i++) {
                Param param = params.get(i);
                if (i < args.length) {
                    param.setValue(args[i]);
                } else {
                    param.setValue(null);
                }
            }
        }

        private void validate(Function<Param, Boolean> validator, String message, Object... args) {
            validators.add(() -> {
                if (!validator.apply(this)) {
                    throw new ParamException(this, Strings.format(message, args));
                }
            });
        }

        public void setValue(String value) {
            this.value = value;

            if (type == ParamType.Required) {
                notNull();
            }

            if (type == ParamType.Optional && value == null) {
                return;
            }

            for (var validator : validators) {
                validator.run();
            }
        }

        private Param(String name, ParamType type, Seq<Runnable> validators) {
            this.name = name;
            this.type = type;
            this.validators = validators;
        }

        public Param copy() {
            return new Param(name, type, validators);
        }

        public String toParamText() {
            if (type == ParamType.Required) {
                return "<" + name + ">";
            }

            if (type == ParamType.Optional) {
                return "[" + name + "]";
            }

            if (type == ParamType.Variadic) {
                return "<" + name + "...>";
            }

            throw new IllegalArgumentException(type.name());
        }

        public String asString() {
            return value;
        }

        public Long asLong() {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ParamException(this, "Invalid int value");
            }
        }

        public Integer asInt() {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new ParamException(this, "Invalid int value");
            }
        }

        public Double asDouble() {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new ParamException(this, "Invalid double value");
            }
        }

        public Float asFloat() {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                throw new ParamException(this, "Invalid float value");
            }
        }

        public boolean hasValue() {
            return value != null;
        }

        public boolean isNull() {
            return value == null;
        }

        public void notNull() {
            if (value == null) {
                throw new ParamException(this, "Missing can not be null" + name);
            }
        }

        public Param minLength(int min) {
            validate(param -> param.value.length() >= min, "Min length: @", min);
            return this;
        }

        public Param maxLength(int max) {
            validate(param -> param.value.length() <= max, "Max length: @", max);
            return this;
        }

        public Param min(double min) {
            validate(param -> param.asDouble() >= min, "Min value: @", min);
            return this;
        }

        public Param max(double max) {
            validate(param -> param.asDouble() <= max, "Max value: @", max);
            return this;
        }

        public String toString() {
            return this.toParamText() + "=" + String.valueOf(value);
        }
    }

    private enum ParamType {
        Required,
        Optional,
        Variadic
    }
}
