package mindustrytool.servermanager.types.response;

import lombok.Data;

@Data
public class ServerPlanDto {
    private int id = 0;
    private String name = "";
    private long ram = 0;
    private float cpu = 0;
}
