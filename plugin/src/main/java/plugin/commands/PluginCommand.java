package plugin.commands;

import arc.struct.Seq;
import arc.util.CommandHandler;
import lombok.Getter;
import lombok.Setter;
import mindustry.gen.Player;

public abstract class PluginCommand {
    @Getter
    @Setter
    private String name;

    @Getter
    @Setter
    private String description;

    private Seq<Param> params = new Seq<>();

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
        Param p = new Param(name, ParamType.Optional);
        params.add(p);
        return p;
    }

    protected Param required(String name) {
        Param p = new Param(name, ParamType.Required);
        params.add(p);
        return p;
    }

    protected Param variadic(String name) {
        Param p = new Param(name, ParamType.Variadic);
        params.add(p);
        return p;
    }

    protected PluginCommand newInstance() {
        try {
            PluginCommand copy = this.getClass().getDeclaredConstructor().newInstance();

            copy.name = this.name;
            copy.description = this.description;

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
            handler.register(name, paramText.toString(), description, (args, player) -> {
                this.newInstance()
                        .handleParams(args)
                        .handleClient((Player) player);
            });
        } else {
            handler.register(name, paramText.toString(), description, (args) -> {
                this.newInstance()
                        .handleParams(args)
                        .handleServer();
            });
        }
    }

    public void handleClient(Player player) {
        throw new UnsupportedOperationException("run");
    }

    public void handleServer() {
        throw new UnsupportedOperationException("run");
    }

    public static class Param {
        private String name;
        private ParamType type;
        private String value;

        public void setValue(String value) {
            this.value = value;
        }

        private Param(String name, ParamType type) {
            this.name = name;
            this.type = type;
        }

        public Param copy() {
            return new Param(name, type);
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
            return Long.parseLong(value);
        }

        public Integer asInt() {
            return Integer.parseInt(value);
        }

        public Float asFloat() {
            return Float.parseFloat(value);
        }
    }

    private static enum ParamType {
        Required,
        Optional,
        Variadic
    }
}
