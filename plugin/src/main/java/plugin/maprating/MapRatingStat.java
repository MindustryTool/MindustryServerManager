package plugin.maprating;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class MapRatingStat {
    public final int totalVotes;
    public final float avgScore;
}
