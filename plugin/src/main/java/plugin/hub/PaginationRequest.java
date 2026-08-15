package plugin.hub;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PaginationRequest {
    private int page;
    private int size;
}
