package com.framelite.mixin;

import com.framelite.AutoTotemFeature;

import net.minecraft.client.gui.screen.ingame.HandledScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * En 1.19.2 {@code InventoryScreen} no redefine {@code keyPressed}, así que el bloqueo del
 * teclado (números de hotbar con stack en el cursor, etc.) va aquí.
 */
@Mixin(HandledScreen.class)
public class HandledScreenLockMixin {
	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void framelite$lockKey(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}
}
