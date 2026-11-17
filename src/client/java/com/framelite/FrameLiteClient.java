package com.framelite;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Punto de entrada del mod (solo cliente).
 *
 * <p>El tick del cliente gestiona la lógica de decisiones y swaps.
 * Se engancha el render de las pantallas de inventario para animar el cursor frame a frame.
 */
public final class FrameLiteClient implements ClientModInitializer {
	private final AutoTotemFeature autoTotem = new AutoTotemFeature();
	private final Set<Screen> hookedScreens = Collections.newSetFromMap(new WeakHashMap<>());

	@Override
	public void onInitializeClient() {
		ClientTickEvents.START_CLIENT_TICK.register(this.autoTotem::tick);

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof InventoryScreen)) {
				return;
			}

			if (this.hookedScreens.add(screen)) {
				ScreenEvents.afterRender(screen).register((renderedScreen, context, mouseX, mouseY, tickDelta) -> {
					if (renderedScreen instanceof InventoryScreen inventoryScreen) {
						this.autoTotem.onScreenRender(client, inventoryScreen);
					}
				});
			}
		});
	}
}
