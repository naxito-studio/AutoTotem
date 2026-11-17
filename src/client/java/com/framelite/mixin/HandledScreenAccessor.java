package com.framelite.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Permite leer el origen absoluto del panel del inventario (necesario para colocar el cursor). */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("x")
	int getX();

	@Accessor("y")
	int getY();
}
