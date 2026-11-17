package com.framelite.mixin;

import com.framelite.AutoTotemFeature;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code InventoryScreen} redefine {@code mouseReleased} en 1.21.11, por lo que se bloquea
 * aquí también para evitar soltar ítems al azar.
 */
@Mixin(InventoryScreen.class)
public class InventoryScreenLockMixin {
	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void framelite$lockMouseRelease(Click click, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}
}
