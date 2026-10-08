package common.player;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PlayerRecordPage {
    public int items;
    public int page;
    public List<PlayerRecord> data;
}
