package plugin.trail;

import plugin.menus.PluginMenu;

import arc.struct.Seq;
import mindustry.gen.Call;
import plugin.core.Registry;
import plugin.session.SessionRepository;
import plugin.utils.Tr;
import plugin.session.Session;

public class TrailMenu extends PluginMenu<Integer> {

    public TrailMenu() {
    }

    @Override
    public void build(Session session, Integer page) {
        var trails = Seq.with(Registry.get(TrailService.class).trails.values());

        var size = 5;

        var maxPage = Math.max(0, (trails.size - 1) / size);
        var currentPage = Math.min(page, maxPage);
        var start = currentPage * size;
        var end = Math.min(start + size, trails.size);

        var hasNoTrail = session.getData().trail == null || session.getData().trail.isEmpty();
        var nonePrefix = hasNoTrail ? "[accent]● [green]" : "[green]";

        if (currentPage == 0) {
            option(nonePrefix + Tr.t(session, "trail.none"), (s, p) -> {
                s.getData().trail = "";
                var repo = Registry.get(SessionRepository.class);
                if (repo != null) {
                    repo.markDirty(s);
                }
                s.player.sendMessage(Tr.t(s, "trail.removed"));
                Call.hideFollowUpMenu(s.player.con, this.getMenuId());
            });
            row();
        }

        for (int i = start; i < end; i++) {
            var trail = trails.get(i);

            var allowed = trail.allowed(session);
            var isCurrent = trail.getName().equals(session.getData().trail);
            var prefix = isCurrent ? "[accent]● [green]" : (allowed ? "[green]" : "[gray]");

            option(prefix + trail.getName(), (s, p) -> {
                if (allowed) {
                    if (trail.getName().equals(s.getData().trail)) {
                        s.getData().trail = "";
                    } else {
                        s.getData().trail = trail.getName();
                    }
                    var repo = Registry.get(SessionRepository.class);
                    if (repo != null) {
                        repo.markDirty(s);
                    }
                }
            });

            for (var req : trail.getRequirements()) {
                text((req.getAllowed().apply(session) ? "[green]" : "[red]") + req.getMessage());
            }
            row();
        }

        option(Tr.t(session, "trail.previous"), (s, p) -> this.send(session, Math.max(0, currentPage - 1)));
        option(Tr.t(session, "trail.next"), (s, p) -> this.send(session, Math.min(maxPage, currentPage + 1)));
        row();
        text(Tr.t(session, "trail.close"));
    }
}
