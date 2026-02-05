package plugin.menus;

import arc.struct.Seq;
import plugin.handler.I18n;
import plugin.handler.TrailHandler;
import plugin.type.Session;

public class TrailMenu extends PluginMenu<Integer> {
    @Override
    public void build(Session session, Integer page) {
        var trails = Seq.with(TrailHandler.trails.values());

        var size = 5;

        var start = page * size;
        var end = Math.min(start + size, trails.size);

        for (int i = start; i < end; i++) {
            var trail = trails.get(i);

            var allowed = trail.allowed(session);
            option((allowed ? "[green]" : "[gray]") + trail.getName(), (p, s) -> {
                session.data().trail = trail.getName();
            });

            for (var req : trail.getRequirements()) {
                text((req.getAllowed().apply(session) ? "[green]" : "[red]") + I18n.t(session, req.getMessage()));
            }
            row();
        }

        option(I18n.t(session, "@Previous"), (p, s) -> this.send(session, Math.max(0, page - 1)));
        option(I18n.t(session, "@Next"), (p, s) -> this.send(session, Math.min(trails.size / size, page + 1)));
        row();
        text(I18n.t(session, "@Close"));
    }
}
