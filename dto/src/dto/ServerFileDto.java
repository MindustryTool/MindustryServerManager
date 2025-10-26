package dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true, chain = true)
public class ServerFileDto {
    public String path;
    public boolean directory;
    public String data;
    public long size;
}
