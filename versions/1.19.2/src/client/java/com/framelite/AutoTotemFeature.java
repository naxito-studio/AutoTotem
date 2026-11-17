package com.framelite;

import com.framelite.mixin.HandledScreenAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * Auto-tótem con dos formas de "popear" (equipar) el tótem, pensado para no morir por ghosting.
 *
 * <ol>
 *     <li><b>Hotbar primero</b>: si hay un tótem en la hotbar se cambia de slot, se hace el swap de
 *     offhand con la tecla F (paquete {@code SWAP_ITEM_WITH_OFFHAND}) y se vuelve al slot de
 *     origen, todo sin abrir el inventario. Es el camino más rápido y el que menos se nota.</li>
 *     <li><b>Inventario</b>: si el tótem está en el inventario principal, se abre el inventario, se
 *     coloca el cursor en el centro del área de 3x9 y se mueve en espiral (una vuelta completa)
 *     hasta el tótem, barriendo las tres líneas. Al llegar se hace el swap y se cierra solo.</li>
 * </ol>
 *
 * <p>El ghosting aparece cuando el cliente ya muestra el tótem en la offhand (predicción local)
 * pero el servidor todavía no lo tiene. Por eso el camino de la hotbar espera la confirmación del
 * servidor antes de reintentar (si se reenviara el swap mientras está en vuelo, se desharía), y el
 * camino del inventario hace el clic en cuanto termina la animación, sin retardos extra.
 */
public final class AutoTotemFeature {
	/**
	 * Vida a partir de la cual se fuerza el tótem aunque la offhand tenga otro objeto.
	 *
	 * <p>No conviene bajarlo: entre el clic y la confirmación del servidor hay un viaje de red de
	 * ida y vuelta, y en ese hueco el jugador tiene que poder aguantar un golpe más.
	 */
	private static final float FORCE_HEALTH = 6.0F;

	/** Botón de {@link SlotActionType#SWAP} que intercambia con la offhand. */
	private static final int SWAP_TO_OFFHAND_BUTTON = 40;

	/** Duración de la espiral del cursor en el inventario. */
	private static final long SPIRAL_MIN_MS = 100L;
	private static final long SPIRAL_MAX_MS = 150L;

	/** Tiempo que se deja el inventario abierto después del swap, para que se vea el abrir/cerrar. */
	private static final long KEEP_OPEN_MS = 100L;

	/**
	 * Tiempo máximo que se espera la confirmación del servidor tras el swap de hotbar. Si no llega,
	 * se reintenta. Es largo a propósito: reenviar el swap antes de tiempo lo desharía.
	 */
	private static final long HOTBAR_PENDING_TIMEOUT_MS = 500L;

	private enum State {
		IDLE,
		MOVING,
		CLICKED,
		CLOSING,
		HOTBAR_PENDING
	}

	private final Random random = new Random();

	private State state = State.IDLE;
	private InventoryScreen openedScreen;
	private CursorAnimation animation;
	private int targetSlotId = -1;

	private long closeAtNanos;
	private long hotbarPendingUntilNanos;

	/**
	 * Inventario que el mod tiene abierto popeando. Mientras está abierto, los mixins de bloqueo
	 * ignoran los clics del jugador sobre la GUI: si el usuario cliquea durante la animación, el
	 * juego mueve lo que tenga el cursor a cualquier slot (por ejemplo a la rejilla de crafteo
	 * 2x2) y desordena el inventario. El clic del mod no pasa por la pantalla, así que no le
	 * afecta el bloqueo.
	 */
	private static HandledScreen<?> lockedScreen;

	// ---------------------------------------------------------------------------------------------
	// Tick del cliente (20 Hz): decide qué camino usar y cierra el inventario
	// ---------------------------------------------------------------------------------------------

	public void tick(MinecraftClient client) {
		long now = System.nanoTime();
		ClientPlayerEntity player = client.player;

		if (player == null || client.world == null || !player.isAlive() || player.isSpectator()) {
			this.shutdown(client);
			return;
		}

		// Si el jugador cerró el inventario que abrimos, o lo reemplazó otra pantalla, olvidarlo.
		if (this.openedScreen != null && client.currentScreen != this.openedScreen) {
			this.resetState();
		}

		switch (this.state) {
			case MOVING -> {
				// La animación avanza en cada frame (onScreenRender); aquí solo vigilamos.
				return;
			}

			case CLICKED -> {
				if (client.currentScreen != this.openedScreen) {
					this.resetState();
					return;
				}

				if (now >= this.closeAtNanos) {
					this.closeOwnScreen(client);
					this.resetState();
				}

				return;
			}

			case CLOSING -> {
				this.closeOwnScreen(client);
				this.resetState();
				return;
			}

			case HOTBAR_PENDING -> {
				// El servidor confirma el swap actualizando la offhand; hasta entonces no se
				// reenvía nada para no deshacerlo.
				if (this.isTotem(player.getOffHandStack())) {
					this.resetState();
					return;
				}

				if (now < this.hotbarPendingUntilNanos) {
					return;
				}

				this.resetState();
			}

			default -> {
			}
		}

		if (client.currentScreen != null) {
			return;
		}

		ItemStack offhand = player.getOffHandStack();

		if (this.isTotem(offhand)) {
			return;
		}

		// Offhand vacía: se rellena siempre. Con otro objeto solo se sustituye en peligro.
		if (!offhand.isEmpty() && player.getHealth() > FORCE_HEALTH) {
			return;
		}

		ScreenHandler handler = player.currentScreenHandler;

		if (handler != player.playerScreenHandler) {
			return;
		}

		// 1) Tótem en la hotbar: cambiar de slot, F y volver al slot de origen.
		int hotbarIndex = this.findHotbarTotem(player, handler);

		if (hotbarIndex >= 0) {
			if (this.swapFromHotbar(client, player, hotbarIndex)) {
				this.state = State.HOTBAR_PENDING;
				this.hotbarPendingUntilNanos = now + msToNanos(HOTBAR_PENDING_TIMEOUT_MS);
			}

			return;
		}

		// 2) Tótem en el inventario principal: abrir, centrar el cursor y espiral hasta el slot.
		int slotId = this.findClosestTotemSlot(player, handler);

		if (slotId >= 0) {
			this.openInventory(client, player, handler, slotId, now);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Camino 1: hotbar
	// ---------------------------------------------------------------------------------------------

	private boolean swapFromHotbar(MinecraftClient client, ClientPlayerEntity player, int hotbarIndex) {
		if (client.getNetworkHandler() == null) {
			return false;
		}

		PlayerInventory inventory = player.getInventory();
		int previous = inventory.selectedSlot;

		// Los paquetes se procesan en orden en el servidor: seleccionar el tótem, hacer el swap
		// con la offhand y volver al slot de origen. El slot local del cliente no se toca, así que
		// el objeto en la mano no parpadea.
		client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarIndex));
		client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
				PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
		client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(previous));
		return true;
	}

	/** Devuelve el índice de hotbar (0..8) de un tótem, el más cercano al slot seleccionado. */
	private int findHotbarTotem(ClientPlayerEntity player, ScreenHandler handler) {
		int selected = player.getInventory().selectedSlot;
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;

		for (Slot slot : handler.slots) {
			if (slot.inventory != player.getInventory() || slot.getIndex() >= 9) {
				continue;
			}

			if (!this.isTotem(slot.getStack())) {
				continue;
			}

			int index = slot.getIndex();
			int distance = Math.abs(index - selected);
			distance = Math.min(distance, 9 - distance);

			if (distance < bestDistance) {
				bestDistance = distance;
				best = index;
			}
		}

		return best;
	}

	// ---------------------------------------------------------------------------------------------
	// Camino 2: inventario con espiral
	// ---------------------------------------------------------------------------------------------

	private void openInventory(MinecraftClient client, ClientPlayerEntity player, ScreenHandler handler,
			int slotId, long now) {
		InventoryScreen screen = new InventoryScreen(player);
		this.openedScreen = screen;
		this.targetSlotId = slotId;
		lockedScreen = screen;
		client.setScreen(screen);

		double[] area = this.inventoryArea(player, handler);

		if (area == null) {
			this.state = State.CLOSING;
			return;
		}

		double centerX = area[0];
		double centerY = area[1];
		double radiusX = area[2];
		double radiusY = area[3];

		// El cursor aparece en el centro del inventario antes de empezar a moverse.
		setCursorGui(client, centerX, centerY);

		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		Slot slot = handler.slots.get(slotId);
		double targetX = accessor.getX() + slot.x + 8.0 + this.randomSigned(1.5);
		double targetY = accessor.getY() + slot.y + 8.0 + this.randomSigned(1.5);

		long duration = this.randomNanos(SPIRAL_MIN_MS, SPIRAL_MAX_MS);
		double turns = this.random.nextBoolean() ? 1.0 : -1.0;

		this.animation = CursorAnimation.spiral(centerX, centerY, targetX, targetY, radiusX, radiusY,
				turns, now, duration);
		this.state = State.MOVING;
	}

	/** Llamado en cada frame mientras hay una pantalla abierta. */
	public void onScreenRender(MinecraftClient client, InventoryScreen screen) {
		if (screen != this.openedScreen || this.state != State.MOVING) {
			return;
		}

		long now = System.nanoTime();

		if (now >= this.animation.getEndNanos()) {
			setCursorGui(client, this.animation.getEndX(), this.animation.getEndY());
			this.clickTarget(client, screen);
			return;
		}

		setCursorGui(client, this.animation.getX(now), this.animation.getY(now));
	}

	private void clickTarget(MinecraftClient client, InventoryScreen screen) {
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interactionManager = client.interactionManager;
		ScreenHandler handler = screen.getScreenHandler();
		int slotId = this.targetSlotId;

		boolean valid = player != null && interactionManager != null
				&& player.currentScreenHandler == handler
				&& slotId >= 0 && slotId < handler.slots.size()
				&& this.isTotem(handler.slots.get(slotId).getStack());

		if (!valid) {
			// El tótem desapareció a mitad de la animación: cerrar desde el tick (nunca desde el
			// callback de render) y volver a intentarlo.
			this.animation = null;
			this.state = State.CLOSING;
			return;
		}

		interactionManager.clickSlot(handler.syncId, slotId, SWAP_TO_OFFHAND_BUTTON,
				SlotActionType.SWAP, player);

		this.animation = null;
		this.state = State.CLICKED;
		this.closeAtNanos = System.nanoTime() + msToNanos(KEEP_OPEN_MS);
	}

	/**
	 * Centro y semiejes del área del inventario principal (3 filas x 9 columnas), en coordenadas
	 * GUI absolutas. Devuelve {@code null} si no se encuentra el área.
	 */
	private double[] inventoryArea(ClientPlayerEntity player, ScreenHandler handler) {
		HandledScreenAccessor accessor = this.openedScreen == null
				? null
				: (HandledScreenAccessor) this.openedScreen;

		if (accessor == null) {
			return null;
		}

		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		boolean found = false;

		for (Slot slot : handler.slots) {
			if (slot.inventory != player.getInventory() || slot.getIndex() < 9 || slot.getIndex() >= 36) {
				continue;
			}

			double x = slot.x + 8.0;
			double y = slot.y + 8.0;
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			found = true;
		}

		if (!found) {
			return null;
		}

		double centerX = accessor.getX() + (minX + maxX) / 2.0;
		double centerY = accessor.getY() + (minY + maxY) / 2.0;

		return new double[] {
				centerX,
				centerY,
				(maxX - minX) / 2.0,
				(maxY - minY) / 2.0
		};
	}

	/**
	 * Devuelve el id del tótem del inventario principal más cercano al centro del área, o {@code -1}.
	 * Así el "popeo" sale del centro hacia los costados, como se pidió.
	 */
	private int findClosestTotemSlot(ClientPlayerEntity player, ScreenHandler handler) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		boolean found = false;

		for (Slot slot : handler.slots) {
			if (slot.inventory != player.getInventory() || slot.getIndex() < 9 || slot.getIndex() >= 36) {
				continue;
			}

			double x = slot.x + 8.0;
			double y = slot.y + 8.0;
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			found = true;
		}

		if (!found) {
			return -1;
		}

		double centerX = (minX + maxX) / 2.0;
		double centerY = (minY + maxY) / 2.0;
		double bestDistance = Double.MAX_VALUE;
		int best = -1;

		for (Slot slot : handler.slots) {
			if (slot.inventory != player.getInventory() || slot.getIndex() < 9 || slot.getIndex() >= 36) {
				continue;
			}

			if (!this.isTotem(slot.getStack())) {
				continue;
			}

			double dx = slot.x + 8.0 - centerX;
			double dy = slot.y + 8.0 - centerY;
			double distance = dx * dx + dy * dy;

			if (distance < bestDistance) {
				bestDistance = distance;
				best = slot.id;
			}
		}

		return best;
	}

	// ---------------------------------------------------------------------------------------------
	// Estado y pantalla
	// ---------------------------------------------------------------------------------------------

	private void shutdown(MinecraftClient client) {
		this.closeOwnScreen(client);
		this.resetState();
	}

	private void resetState() {
		this.state = State.IDLE;
		this.openedScreen = null;
		lockedScreen = null;
		this.animation = null;
		this.targetSlotId = -1;
		this.closeAtNanos = 0L;
		this.hotbarPendingUntilNanos = 0L;
	}

	private void closeOwnScreen(MinecraftClient client) {
		InventoryScreen screen = this.openedScreen;

		if (screen != null && client.currentScreen == screen && client.player != null) {
			screen.close();
		}
	}

	private boolean isTotem(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == Items.TOTEM_OF_UNDYING;
	}

	/** Consultado por los mixins de bloqueo: ¿la pantalla abierta es la del mod? */
	public static boolean isInputLocked() {
		MinecraftClient client = MinecraftClient.getInstance();
		return lockedScreen != null && client.currentScreen == lockedScreen;
	}

	// ---------------------------------------------------------------------------------------------
	// Cursor real (GLFW) en coordenadas GUI
	// ---------------------------------------------------------------------------------------------

	private static void setCursorGui(MinecraftClient client, double guiX, double guiY) {
		var window = client.getWindow();

		if (window.getWidth() <= 0 || window.getHeight() <= 0
				|| window.getScaledWidth() <= 0 || window.getScaledHeight() <= 0) {
			return;
		}

		double windowX = guiX * window.getWidth() / window.getScaledWidth();
		double windowY = guiY * window.getHeight() / window.getScaledHeight();

		GLFW.glfwSetCursorPos(window.getHandle(), windowX, windowY);
	}

	// ---------------------------------------------------------------------------------------------
	// Aleatoriedad
	// ---------------------------------------------------------------------------------------------

	private long randomNanos(long minMs, long maxMs) {
		return (long) ((minMs + this.random.nextDouble() * (maxMs - minMs)) * 1_000_000.0);
	}

	private double randomSigned(double magnitude) {
		return this.random.nextBoolean() ? magnitude : -magnitude;
	}

	private static long msToNanos(long millis) {
		return millis * 1_000_000L;
	}
}
