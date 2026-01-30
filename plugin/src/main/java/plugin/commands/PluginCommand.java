package plugin.commands;

import java.util.function.Function;

import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import lombok.Getter;
import lombok.Setter;
import mindustry.gen.Player;
import plugin.ServerController;

public abstract class PluginCommand {
    @Getter
    @Setter
    private String name;

    @Getter
    @Setter
    private String description;

    private Seq<Param> params = new Seq<>();

    private Player player;

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

    protected PluginCommand newInstance(Player player) {
        try {
            PluginCommand copy = this.getClass().getDeclaredConstructor().newInstance();

            copy.name = this.name;
            copy.description = this.description;
            copy.player = player;

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
                        player.sendMessage("[scarlet]You must be admin to use this command.");
                        return;
                    }

                    wrapper(() -> {
                        this.newInstance((Player) player)
                                .handleParams(args)
                                .handleClient((Player) player);
                    });
                } else {
                    throw new IllegalArgumentException("Player expected");
                }
            });
        } else {
            wrapper(() -> {
                handler.register(name, paramText.toString(), description, (args) -> {
                    this.newInstance(null)
                            .handleParams(args)
                            .handleServer();
                });
            });
        }
    }

    private void wrapper(Runnable runnable) {
        try {
            ServerController.BACKGROUND_TASK_EXECUTOR.submit(runnable);
        } catch (ParamException e) {
            if (player != null) {
                player.sendMessage(e.getMessage());
            }
        } catch (Exception e) {
            if (player != null) {
                player.sendMessage("Error");
            }
            Log.err("Failed to execute command " + name, e);
        }
    }

    public void handleClient(Player player) {
        throw new UnsupportedOperationException("run");
    }

    public void handleServer() {
        throw new UnsupportedOperationException("run");
    }

    public static class ParamException extends IllegalArgumentException {
        public ParamException(Param param, String message) {
            super(message + "\n" + param.toParamText() + "=" + param.value);
        }
    }

    public static class Param {
        private String name;
        private ParamType type;
        private String value;

        private Seq<Function<Param, Boolean>> validators = new Seq<>();

        public void setValue(String value) {
            this.value = value;

            if (type == ParamType.Optional) {
                return;
            }

            if (type == ParamType.Required) {
                notNull();
            }

            for (Function<Param, Boolean> validator : validators) {
                if (!validator.apply(this)) {
                    throw new ParamException(this, "Invalid value");
                }
            }
        }

        private Param(String name, ParamType type, Seq<Function<Param, Boolean>> validators) {
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
            validators.add(param -> param.value.length() >= min);
            return this;
        }

        public Param maxLength(int max) {
            validators.add(param -> param.value.length() <= max);
            return this;
        }

        public Param min(double min) {
            validators.add(param -> param.asDouble() >= min);
            return this;
        }

        public Param max(double max) {
            validators.add(param -> param.asDouble() <= max);
            return this;
        }
    }

    private static enum ParamType {
        Required,
        Optional,
        Variadic
    }
}
