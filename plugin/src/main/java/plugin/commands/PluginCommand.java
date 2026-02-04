package plugin.commands;

import java.util.function.Function;

import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import lombok.Getter;
import lombok.Setter;
import mindustry.gen.Player;
import plugin.ServerControl;
import plugin.handler.I18n;
import plugin.utils.Utils;
import plugin.handler.SessionHandler;
import plugin.type.Session;

public abstract class PluginCommand {
    @Getter
    @Setter
    private String name;

    @Getter
    @Setter
    private String description;

    private Seq<Param> params = new Seq<>();

    @Setter
    @Getter
    private boolean admin = true;

    private PluginCommand handleParams(String[] args) {
        for (int i = 0; i < params.size; i++) {
            Param param = params.get(i);
            if (i < args.length) {
                param.setValue(args[i]);
            } else {
                param.setValue(null);
            }
        }

        return this;
    }

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

    protected PluginCommand newInstance() {
        try {
            PluginCommand copy = this.getClass().getDeclaredConstructor().newInstance();

            copy.name = this.name;
            copy.description = (admin ? "[scarlet]ADMIN - " : "") + this.description;

            for (Param p : this.params) {
                copy.params.add(p.copy());
            }

            return copy;
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone command", e);
        }
    }

    public void register(CommandHandler handler, boolean isClient) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }

        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("description");
        }

        StringBuilder paramText = new StringBuilder("");

        for (Param param : params) {
            paramText.append(param.toParamText()).append(" ");
        }

        if (isClient) {
            handler.register(name, paramText.toString(), description, (args, p) -> {
                if (p instanceof Player player) {
                    if (admin && !player.admin) {
                        player.sendMessage(I18n.t(Utils.parseLocale(player.locale()), "[scarlet]",
                                "@You must be admin to use this command."));
                        return;
                    }

                    var session = SessionHandler.get(player).orElse(null);

                    if (session == null) {
                        Log.info("[scarlet]Failed to get session for player.");
                        Thread.dumpStack();
                        return;
                    }

                    ServerControl.ioTask("Client command", () -> {
                        try {
                            this.newInstance()
                                    .handleParams(args)
                                    .handleClient(session);
                        } catch (ParamException e) {
                            session.player.sendMessage(I18n.t(
                                    session.locale, "[scarlet]", "@Error: ", e.getMessage()));
                        } catch (Exception e) {
                            session.player.sendMessage(I18n.t(
                                    session.locale, "[scarlet]", "@Error"));
                            Log.err("Failed to execute command " + name, e);
                        }
                    });
                } else {
                    throw new IllegalArgumentException("Player expected");
                }
            });
        } else {
            handler.register(name, paramText.toString(), description, (args) -> {
                ServerControl.ioTask("Server command", () -> {
                    try {
                        this.newInstance()
                                .handleParams(args)
                                .handleServer();
                    } catch (ParamException e) {
                        Log.err(e.getMessage());
                    } catch (Exception e) {
                        Log.err("Failed to execute command " + name, e);
                    }
                });
            });
        }
    }

    public void handleClient(Session session) {
        throw new UnsupportedOperationException("run");
    }

    public void handleServer() {
        throw new UnsupportedOperationException("run");
    }

    public static class ParamException extends IllegalArgumentException {
        public ParamException(Param param, String message) {
            super(message + " " + param.toParamText() + "=" + param.value);
        }
    }

    public static class Param {
        private String name;
        private ParamType type;
        private String value;

        private Seq<Runnable> validators = new Seq<>();

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

    private static enum ParamType {
        Required,
        Optional,
        Variadic
    }
}
