package plugin.type;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MenuData {
    private int id;
    private String title;
    private String description;
    private String[][] optionTexts;
    private List<PlayerPressCallback> callbacks;
    private Object state;
}
