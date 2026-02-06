package plugin.gamemode.catali.data;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TeamUpgrades {
    // Stat multipliers (1.0 = base)
    public float healthMultiplier = 1.0f;
    public float damageMultiplier = 1.0f;
    public float regenMultiplier = 1.0f; // This might need custom logic to apply
    public float expMultiplier = 1.0f;

    // Upgrade levels for tracking
    public int healthLevel = 0;
    public int damageLevel = 0;
    public int regenLevel = 0;
    public int expLevel = 0;
}
