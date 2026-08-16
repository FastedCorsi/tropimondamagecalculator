package fr.tropimon.damagecalc.mixin;

import fr.tropimon.damagecalc.TropimonDamageCalcClient;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(targets = "com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket", remap = false)
public abstract class BattleMessagePacketMixin {
    @Unique
    private boolean tropimonDamageCalc$tracked;

    @Inject(method = "getMessages", at = @At("RETURN"), remap = false)
    private void tropimonDamageCalc$trackMessages(CallbackInfoReturnable<List<Text>> cir) {
        if (tropimonDamageCalc$tracked) {
            return;
        }
        tropimonDamageCalc$tracked = true;
        TropimonDamageCalcClient.trackBattleMessages(cir.getReturnValue());
    }
}
