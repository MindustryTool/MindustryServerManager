package plugin.gamemode.catali.data;

import lombok.Data;
import lombok.NoArgsConstructor;
import mindustry.gen.Unit;

@NoArgsConstructor
@Data
public class TeamUpgrades {
    private float baseHealthMultiplier = 0.05f;
    private float baseDamageMultiplier = 0.05f;
    private float baseRegenMultiplier = 0.1f; // This might need custom logic to apply
    private float baseExpMultiplier = 0.1f;

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
        return baseDamageMultiplier * damageLevel + 1;
    }

    public float getRegenMultiplier() {
        return baseRegenMultiplier * regenLevel + 1;
    }

    public float getExpMultiplier() {
        return baseExpMultiplier * expLevel + 1;
    }

    public float getHealthMultiplier() {
        return baseHealthMultiplier * healthLevel + 1;
    }

    public void levelUpHealth(int amount) {
        healthLevel += amount;
    }

    public void levelUpDamage(int amount) {
        damageLevel += amount;
    }

    public void levelUpRegen(int amount) {
        regenLevel += amount;
    }

    public void levelUpExp(int amount) {
        expLevel += amount;
    }
}
