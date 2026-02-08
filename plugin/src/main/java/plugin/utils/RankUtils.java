package plugin.utils;

import java.util.Locale;

import arc.struct.Seq;
import plugin.repository.SessionRepository.RankData;
import plugin.service.I18n;

public class RankUtils {
    public static String getRankString(Locale locale, Seq<RankData> players) {
        StringBuilder sb = new StringBuilder("[white]\n");

        sb.append(I18n.t(locale, "[accent]", Utils.padRight("@Rank", 10)))
                .append(Utils.padRight("Exp", 20))
                .append("[white]\n");

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

            sb.append(Utils.padRight(rank, 10))
                    .append("[white] ")
                    .append(Utils.padRight("[" + levelStr + "] (" + totalExp + ")", 20))
                    .append("[white] ")
                    .append(data.name)
                    .append("[white]\n");
        }

        return sb.append("[white]\n").toString();
    }
}
