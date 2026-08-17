package plugin.trail;

import plugin.menus.PluginMenu;

import arc.struct.Seq;
import plugin.core.Registry;
import plugin.utils.Tr;
import plugin.session.Session;

public class TrailMenu extends PluginMenu<Integer> {

    public TrailMenu() {
    }

    @Override
    public void build(Session session, Integer page) {
        var trails = Seq.with(Registry.get(TrailService.class).trails.values());

        var size = 5;

        var start = page * size;
        var end = Math.min(start + size, trails.size);

        for (int i = start; i < end; i++) {
            var trail = trails.get(i);

            var allowed = trail.allowed(session);
            option((allowed ? "[green]" : "[gray]") + trail.getName(), (p, s) -> {
                session.getData().trail = trail.getName();
            });

            for (var req : trail.getRequirements()) {
                text((req.getAllowed().apply(session) ? "[green]" : "[red]") + req.getMessage());
            }
            row();
        }

        option(Tr.t(session, "trail.previous"), (p, s) -> this.send(session, Math.max(0, page - 1)));
        option(Tr.t(session, "trail.next"), (p, s) -> this.send(session, Math.min(trails.size / size, page + 1)));
        row();
        text(Tr.t(session, "trail.close"));
    }
}
