package plugin.gamemode.catali.data;

import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import mindustry.gen.Unit;

@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class TeamUpgrades {
    // Stat multipliers (1.0 = base)
    private float healthMultiplier = 1.0f;
    private float damageMultiplier = 1.0f;
    private float regenMultiplier = 1.0f; // This might need custom logic to apply
    private float expMultiplier = 1.0f;

    // Upgrade levels for tracking
    private int healthLevel = 0;
    private int damageLevel = 0;
    private int regenLevel = 0;
    private int expLevel = 0;

    public void apply(Unit unit) {
        unit.maxHealth = unit.type.health * getHealthMultiplier();
        unit.health = unit.maxHealth;
        unit.damageMultiplier(getDamageMultiplier());
    }

    public float getDamageMultiplier() {
        return damageMultiplier * damageLevel + 1;
    }

    public float getRegenMultiplier() {
        return regenMultiplier * regenLevel + 1;
    }

    public float getExpMultiplier() {
        return expMultiplier * expLevel + 1;
    }

    public float getHealthMultiplier() {
        return healthMultiplier * healthLevel + 1;
    }

    public void levelUpHealth(int amount) {
        damageLevel += amount;
    }

    public void levelUpDamage(int amount) {
        damageLevel += amount;
    }

    public void levelUpHealing(int amount) {
        regenLevel += amount;
    }

    public void levelUpExp(int amount) {
        expLevel += amount;
    }
}
