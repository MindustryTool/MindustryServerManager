package plugin.gamemode.catali.data;

import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import mindustry.gen.Unit;

@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class TeamUpgrades {
    private float baseHealthMultiplier = 0.05f;
    private float baseDamageMultiplier = 0.05f;
    private float baseRegenMultiplier = 0.05f; // This might need custom logic to apply
    private float baseExpMultiplier = 0.05f;

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
        damageLevel += amount;
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
