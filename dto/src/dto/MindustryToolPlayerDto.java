package dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MindustryToolPlayerDto {
    String uuid;
    boolean admin;
    String name;
    String loginLink;
    long exp;
}
