package server.types.data;

import lombok.Data;

@Data
public class PaginationRequest {
    private int page;
    private int size;
}
