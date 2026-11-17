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
 * Auto-tótem con dos caminos:
 *
 * <ol>
 *     <li><b>Hotbar</b>: si el tótem está en la hotbar, se realiza la animación visual de cambio
 *     de slot (held-item animation), se envía el swap de offhand con la tecla F por paquetes y se
 *     vuelve al slot original. Todo sin abrir inventario.</li>
 *     <li><b>Inventario en Espiral (Sin popear en fila)</b>: si el tótem está en el inventario
 *     principal (slots 9-35), se abre la pantalla del inventario, se bloquea toda entrada del
 *     usuario (evitando clics accidentales o mandar ítems a la mesa de crafteo), se mueve el cursor
 *     en espiral desde el centro hacia el tótem, se hace el swap con la offhand y se cierra
 *     automáticamente.</li>
 * </ol>
 *
 * <p><b>Selección en espiral vs Popeo en fila:</b>
 * La selección de tótems en el inventario sigue {@link #SPIRAL_SLOT_ORDER}, consumiendo tótems
 * desde el centro (slot 22) hacia los anillos exteriores en un patrón circular continuo.
 * Nunca vacía el inventario en líneas horizontales (9 -> 10 -> 11...), lo que resulta natural y
 * evita patrones detectables.
 *
 * <p><b>Anti-Ghost y estabilidad en partidas de Drop:</b>
 * <ul>
 *     <li>Re-validación en tiempo real del slot objetivo antes del clic por si entran drops durante
 *     el movimiento.</li>
 *     <li>Confirmación pasiva en la offhand sin emitir paquetes destructivos como DROP_ITEM.</li>
 *     <li>Bloqueo completo de mouseDragged y teclas en mixins para proteger la cuadrícula 2x2.</li>
 * </ul>
 */
public final class AutoTotemFeature {
	private static final float FORCE_HEALTH = 6.0F;

	/** Botón de {@link SlotActionType#SWAP} que intercambia con la offhand. */
	private static final int SWAP_TO_OFFHAND_BUTTON = 40;

	// ── Tiempos y animación ──────────────────────────────────────────────────
	private static final long HOTBAR_ANIM_HOLD_MS = 80L;
	private static final long SPIRAL_MIN_MS = 90L;
	private static final long SPIRAL_MAX_MS = 140L;
	private static final long KEEP_OPEN_MS = 70L;
	private static final long PENDING_TIMEOUT_MS = 600L;

	/**
	 * Orden de slots en espiral continua desde el centro (slot 22) hacia los bordes.
	 * Recorre los 27 slots del inventario principal (9 al 35) de forma circular:
	 * Centro -> Anillo interior -> Anillo medio -> Esquinas exteriores.
	 * Totalmente inmune a "popear en fila".
	 */
	private static final int[] SPIRAL_SLOT_ORDER = {
			22, 23, 32, 31, 30, 21, 12, 13, 14,
			24, 33, 29, 20, 11, 15,
			25, 34, 28, 19, 10, 16,
			26, 35, 27, 18, 9, 17
	};

	private enum State {
		IDLE,
		HOTBAR_ANIM,
		HOTBAR_PENDING,
		MOVING,
		CLICKED,
		CLOSING,
		INVENTORY_PENDING
	}

	private final Random random = new Random();

	private State state = State.IDLE;
	private InventoryScreen openedScreen;
	private CursorAnimation animation;
	private int targetSlotId = -1;

	private int originalSlot = -1;
	private int totemHotbarSlot = -1;
	private long nextStepNanos;
	private long pendingUntilNanos;

	private static HandledScreen<?> lockedScreen;

	// ─────────────────────────────────────────────────────────────────────────
	// Tick principal (20 Hz)
	// ─────────────────────────────────────────────────────────────────────────

	public void tick(MinecraftClient client) {
		long now = System.nanoTime();
		ClientPlayerEntity player = client.player;

		if (player == null || client.world == null || !player.isAlive() || player.isSpectator()) {
			this.shutdown(client);
			return;
		}

		// Si el jugador o el juego cerró la pantalla que abrimos, resetear estado
		if (this.openedScreen != null && client.currentScreen != this.openedScreen) {
			this.resetState(client);
		}

		switch (this.state) {
			case HOTBAR_ANIM -> {
				this.tickHotbarAnim(client, player, now);
				return;
			}

			case HOTBAR_PENDING -> {
				this.tickPending(player, now);
				return;
			}

			case MOVING -> {
				// Avanza en cada frame vía onScreenRender
				return;
			}

			case CLICKED -> {
				if (client.currentScreen != this.openedScreen) {
					this.resetState(client);
					return;
				}

				if (now >= this.nextStepNanos) {
					this.closeOwnScreen(client);
					this.state = State.INVENTORY_PENDING;
					this.pendingUntilNanos = now + msToNanos(PENDING_TIMEOUT_MS);
				}

				return;
			}

			case CLOSING -> {
				this.closeOwnScreen(client);
				this.resetState(client);
				return;
			}

			case INVENTORY_PENDING -> {
				this.tickPending(player, now);
				return;
			}

			default -> {
			}
		}

		// ── IDLE: Evaluar si se necesita reponer tótem ────────────────────────

		// No interferir si hay otra pantalla abierta ajena al mod (cofre, anvil, etc.)
		if (client.currentScreen != null) {
			return;
		}

		ItemStack offhand = player.getOffHandStack();

		if (this.isTotem(offhand)) {
			return;
		}

		// Si la offhand no está vacía, solo reemplazar si la vida es baja
		if (!offhand.isEmpty() && player.getHealth() > FORCE_HEALTH) {
			return;
		}

		ScreenHandler handler = player.currentScreenHandler;

		if (handler != player.playerScreenHandler) {
			return;
		}

		// Si el cursor del jugador ya está sosteniendo un ítem, esperar para no interferir
		if (!handler.getCursorStack().isEmpty()) {
			return;
		}

		// 1) Prioridad: Tótem en la hotbar (animación visual de held-item y swap rápido)
		int hotbarIndex = this.findHotbarTotem(player, handler);

		if (hotbarIndex >= 0) {
			this.beginHotbarAnim(client, player, hotbarIndex, now);
			return;
		}

		// 2) Tótem en el inventario principal: abrir GUI, espiral del cursor y swap en espiral
		int slotId = this.findSpiralTotemSlot(player, handler);

		if (slotId >= 0) {
			this.openInventory(client, player, handler, slotId, now);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Camino 1: Hotbar con animación visual de held-item
	// ─────────────────────────────────────────────────────────────────────────

	private void beginHotbarAnim(MinecraftClient client, ClientPlayerEntity player,
			int hotbarIndex, long now) {
		if (client.getNetworkHandler() == null) {
			return;
		}

		PlayerInventory inventory = player.getInventory();
		this.originalSlot = inventory.getSelectedSlot();
		this.totemHotbarSlot = hotbarIndex;

		// Cambio visual de held-item
		inventory.setSelectedSlot(hotbarIndex);
		client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarIndex));

		this.state = State.HOTBAR_ANIM;
		this.nextStepNanos = now + msToNanos(HOTBAR_ANIM_HOLD_MS + this.randomJitter(20));
	}

	private void tickHotbarAnim(MinecraftClient client, ClientPlayerEntity player, long now) {
		if (now < this.nextStepNanos) {
			return;
		}

		if (client.getNetworkHandler() == null) {
			this.resetState(client);
			return;
		}

		// Swap con offhand (F) y regreso inmediato al slot original
		client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
				PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));

		player.getInventory().setSelectedSlot(this.originalSlot);
		client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(this.originalSlot));

		this.state = State.HOTBAR_PENDING;
		this.pendingUntilNanos = now + msToNanos(PENDING_TIMEOUT_MS);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Camino 2: Inventario con GUI y espiral
	// ─────────────────────────────────────────────────────────────────────────

	private void openInventory(MinecraftClient client, ClientPlayerEntity player,
			ScreenHandler handler, int slotId, long now) {
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

		// Centrar el cursor en el centro del inventario (slot 22)
		setCursorGui(client, centerX, centerY);

		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		Slot slot = handler.slots.get(slotId);
		double targetX = accessor.getX() + slot.x + 8.0 + this.randomSigned(1.5);
		double targetY = accessor.getY() + slot.y + 8.0 + this.randomSigned(1.5);

		long duration = this.randomNanos(SPIRAL_MIN_MS, SPIRAL_MAX_MS);
		double turns = this.random.nextBoolean() ? 1.0 : -1.0;

		this.animation = CursorAnimation.spiral(centerX, centerY, targetX, targetY,
				radiusX, radiusY, turns, now, duration);
		this.state = State.MOVING;
	}

	/** Llamado cada frame mientras la pantalla está activa. */
	public void onScreenRender(MinecraftClient client, InventoryScreen screen) {
		if (screen != this.openedScreen || this.state != State.MOVING || this.animation == null) {
			return;
		}

		long now = System.nanoTime();

		if (now >= this.animation.getEndNanos()) {
			setCursorGui(client, this.animation.getEndX(), this.animation.getEndY());
			this.clickTarget(client, screen, now);
			return;
		}

		setCursorGui(client, this.animation.getX(now), this.animation.getY(now));
	}

	private void clickTarget(MinecraftClient client, InventoryScreen screen, long now) {
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interactionManager = client.interactionManager;
		ScreenHandler handler = screen.getScreenHandler();

		if (player == null || interactionManager == null || player.currentScreenHandler != handler) {
			this.state = State.CLOSING;
			return;
		}

		// Re-verificación dinámica: si entraron drops mientras se movía el cursor,
		// confirmar si targetSlotId aún tiene tótem o buscar el siguiente tótem en espiral.
		int actualSlotId = this.targetSlotId;

		if (actualSlotId < 0 || actualSlotId >= handler.slots.size()
				|| !this.isTotem(handler.slots.get(actualSlotId).getStack())) {
			actualSlotId = this.findSpiralTotemSlot(player, handler);
		}

		if (actualSlotId < 0) {
			this.animation = null;
			this.state = State.CLOSING;
			return;
		}

		// Swap seguro de la casilla del tótem con la offhand (botón 40 = offhand)
		interactionManager.clickSlot(handler.syncId, actualSlotId, SWAP_TO_OFFHAND_BUTTON,
				SlotActionType.SWAP, player);

		this.animation = null;
		this.state = State.CLICKED;
		this.nextStepNanos = now + msToNanos(KEEP_OPEN_MS);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Anti-Ghost y confirmación
	// ─────────────────────────────────────────────────────────────────────────

	private void tickPending(ClientPlayerEntity player, long now) {
		// Confirmación exitosa en la offhand
		if (this.isTotem(player.getOffHandStack())) {
			this.resetState(MinecraftClient.getInstance());
			return;
		}

		// Timeout: volver a IDLE para permitir reintento en el siguiente tick
		if (now >= this.pendingUntilNanos) {
			this.resetState(MinecraftClient.getInstance());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Búsqueda de slots (Espiral y Hotbar)
	// ─────────────────────────────────────────────────────────────────────────

	private int findHotbarTotem(ClientPlayerEntity player, ScreenHandler handler) {
		int selected = player.getInventory().getSelectedSlot();
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

	/**
	 * Encuentra el siguiente tótem en el inventario siguiendo el orden en espiral desde el centro.
	 * Totalmente inmune a "popear en fila".
	 */
	private int findSpiralTotemSlot(ClientPlayerEntity player, ScreenHandler handler) {
		for (int slotId : SPIRAL_SLOT_ORDER) {
			if (slotId >= handler.slots.size()) {
				continue;
			}

			Slot slot = handler.slots.get(slotId);

			if (slot.inventory == player.getInventory() && this.isTotem(slot.getStack())) {
				return slotId;
			}
		}

		return -1;
	}

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

	// ─────────────────────────────────────────────────────────────────────────
	// Estado y control de pantalla
	// ─────────────────────────────────────────────────────────────────────────

	public static boolean isInputLocked() {
		MinecraftClient client = MinecraftClient.getInstance();
		return lockedScreen != null && client.currentScreen == lockedScreen;
	}

	private void shutdown(MinecraftClient client) {
		this.closeOwnScreen(client);
		this.resetState(client);
	}

	private void resetState(MinecraftClient client) {
		// Si se abortó una animación de hotbar, restaurar slot seleccionado
		if (this.originalSlot >= 0 && client != null && client.player != null
				&& (this.state == State.HOTBAR_ANIM || this.state == State.HOTBAR_PENDING)) {
			PlayerInventory inv = client.player.getInventory();

			if (inv.getSelectedSlot() != this.originalSlot) {
				inv.setSelectedSlot(this.originalSlot);

				if (client.getNetworkHandler() != null) {
					client.getNetworkHandler().sendPacket(
							new UpdateSelectedSlotC2SPacket(this.originalSlot));
				}
			}
		}

		this.state = State.IDLE;
		this.openedScreen = null;
		lockedScreen = null;
		this.animation = null;
		this.targetSlotId = -1;
		this.originalSlot = -1;
		this.totemHotbarSlot = -1;
		this.nextStepNanos = 0L;
		this.pendingUntilNanos = 0L;
	}

	private void closeOwnScreen(MinecraftClient client) {
		InventoryScreen screen = this.openedScreen;

		if (screen != null && client.currentScreen == screen && client.player != null) {
			screen.close();
		}

		this.openedScreen = null;
		lockedScreen = null;
	}

	private boolean isTotem(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == Items.TOTEM_OF_UNDYING;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Cursor y matemáticas
	// ─────────────────────────────────────────────────────────────────────────

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

	private long randomNanos(long minMs, long maxMs) {
		return (long) ((minMs + this.random.nextDouble() * (maxMs - minMs)) * 1_000_000.0);
	}

	private long randomJitter(long maxMs) {
		return (long) (this.random.nextDouble() * maxMs);
	}

	private double randomSigned(double magnitude) {
		return this.random.nextBoolean() ? magnitude : -magnitude;
	}

	private static long msToNanos(long millis) {
		return millis * 1_000_000L;
	}
}
