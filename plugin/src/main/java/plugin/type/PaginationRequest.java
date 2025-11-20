package plugin.type;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PaginationRequest {
    private int page;
    private int size;
}
