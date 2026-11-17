package com.framelite.mixin;

import com.framelite.AutoTotemFeature;

import net.minecraft.client.gui.screen.ingame.InventoryScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mientras el mod tiene su inventario abierto popeando, se ignoran los clics del jugador sobre
 * la GUI: soltar un stack del cursor en medio de la animación es lo que deja tótems repartidos
 * por el inventario (o en la rejilla de crafteo 2x2).
 */
@Mixin(InventoryScreen.class)
public class InventoryScreenLockMixin {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void framelite$lockMouseClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void framelite$lockMouseRelease(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}
}
