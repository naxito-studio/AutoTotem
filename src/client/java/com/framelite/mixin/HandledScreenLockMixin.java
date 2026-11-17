package com.framelite.mixin;

import com.framelite.AutoTotemFeature;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.KeyInput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bloquea cualquier interacción manual del usuario sobre la GUI mientras el mod está
 * ejecutando la animación del cursor o el swap:
 * - mouseClicked
 * - mouseDragged (evita que movimientos del cursor con clic sostenido arrastren ítems al crafteo)
 * - mouseReleased
 * - mouseScrolled
 * - keyPressed
 */
@Mixin(HandledScreen.class)
public class HandledScreenLockMixin {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void framelite$lockMouseClick(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void framelite$lockMouseDrag(Click click, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void framelite$lockMouseRelease(Click click, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
	private void framelite$lockMouseScroll(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void framelite$lockKey(KeyInput keyInput, CallbackInfoReturnable<Boolean> cir) {
		if (AutoTotemFeature.isInputLocked()) {
			cir.setReturnValue(true);
		}
	}
}
