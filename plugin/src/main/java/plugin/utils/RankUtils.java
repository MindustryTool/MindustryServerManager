package plugin.utils;

import arc.struct.Seq;
import plugin.repository.SessionRepository.RankData;

public class RankUtils {
    public static String getRankString(Seq<RankData> players) {
        StringBuilder sb = new StringBuilder("[]\n");

        for (int i = 0; i < players.size; i++) {
            var data = players.get(i).data;
            int pos = i + 1;

            String rank;
            if (i == 0)
                rank = "[gold]1st";
            else if (i == 1)
                rank = "[#C0C0C0]2nd";
            else if (i == 2)
                rank = "[#CD7F32]3rd";
            else {
                if (pos % 10 == 1 && pos % 100 != 11)
                    rank = pos + "st";
                else if (pos % 10 == 2 && pos % 100 != 12)
                    rank = pos + "nd";
                else if (pos % 10 == 3 && pos % 100 != 13)
                    rank = pos + "rd";
                else
                    rank = pos + "th";
            }

            long totalExp = ExpUtils.getTotalExp(data, 0);
            String levelStr = String.valueOf(ExpUtils.levelFromTotalExp(totalExp));

            sb.append(Utils.padRight(rank, 6))
                    .append("[] ")
                    .append(Utils.padRight(data.name, 20))
                    .append("[] ")
                    .append("[[")
                    .append(Utils.padRight(levelStr, 3))
                    .append("] (")
                    .append(totalExp)
                    .append(")\n");
        }

        return sb.append("[]\n").toString();
    }
}
