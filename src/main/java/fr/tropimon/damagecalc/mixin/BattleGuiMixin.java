package fr.tropimon.damagecalc.mixin;

import fr.tropimon.damagecalc.TropimonDamageCalcClient;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.cobblemon.mod.common.client.gui.battle.BattleGUI", remap = false)
public abstract class BattleGuiMixin {
    @Inject(method = "method_25394", at = @At("TAIL"), remap = false)
    private void tropimonDamageCalc$renderButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        TropimonDamageCalcClient.renderBattleGuiButton(context, mouseX, mouseY, this);
    }

    @Inject(method = "method_25402", at = @At("HEAD"), cancellable = true, remap = false)
    private void tropimonDamageCalc$clickButton(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (TropimonDamageCalcClient.handleBattleGuiButtonClick(mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }
}
