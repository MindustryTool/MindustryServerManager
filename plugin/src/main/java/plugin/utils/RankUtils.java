package plugin.utils;

import arc.struct.Seq;
import mindustry.gen.Call;
import plugin.repository.SessionRepository;
import plugin.type.SessionData;

public class RankUtils {

    public static void sendLeaderBoard() {
        Seq<SessionData> players = SessionRepository.getLeaderBoard(10);
        Call.sendMessage(getRankString(players));
    }

    public static String getRankString(Seq<SessionData> players) {
        StringBuilder sb = new StringBuilder("[]\n");

        for (int i = 0; i < players.size; i++) {
            var data = players.get(i);

            if (i == 0) {
                sb.append("[gold]1st");
            } else if (i == 1) {
                sb.append("[#C0C0C0]2nd");
            } else if (i == 2) {
                sb.append("[#CD7F32]3rd");
            } else {
                sb.append(i + 1 + "th");
            }

            long totalExp = ExpUtils.getTotalExp(data, 0);

            sb.append("[] ")
                    .append(data.name)
                    .append("[] Lv: ")
                    .append(ExpUtils.levelFromTotalExp(totalExp))
                    .append("(")
                    .append(totalExp)
                    .append(")\n");
        }

        sb.append("[]\n");

        return sb.toString();
    }
}
