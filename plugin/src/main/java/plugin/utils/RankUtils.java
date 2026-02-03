package plugin.utils;

import arc.struct.Seq;
import plugin.repository.SessionRepository.RankData;

public class RankUtils {

    public static String getRankString(Seq<RankData> players) {
        StringBuilder sb = new StringBuilder("[]\n");

        for (int i = 0; i < players.size; i++) {
            var data = players.get(i).data;

            String rank = i + 1 + "th";

            if (i == 0) {
                rank = "[gold]1st";
            } else if (i == 1) {
                rank = "[#C0C0C0]2nd";
            } else if (i == 2) {
                rank = "[#CD7F32]3rd";
            }

            sb.append(Utils.padRight(rank, 5));

            long totalExp = ExpUtils.getTotalExp(data, 0);

            sb.append("[] ")
                    .append(Utils.padRight(data.name, 20))
                    .append("[] ")
                    .append(Utils.padRight(ExpUtils.levelFromTotalExp(totalExp) + "", 3))
                    .append("(")
                    .append(totalExp)
                    .append(")\n");
        }

        sb.append("[]\n");

        return sb.toString();
    }
}
