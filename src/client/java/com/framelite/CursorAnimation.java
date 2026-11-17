package com.framelite;

/**
 * Animación del cursor en espiral desde el centro del inventario hasta el slot del tótem.
 *
 * <p>El movimiento se calcula a partir de {@link System#nanoTime()}, independiente de los FPS.
 * La trayectoria es una espiral: el radio crece desde 0 (centro) hasta el objetivo mientras el
 * ángulo gira, de modo que el cursor barre las líneas del inventario de forma natural.
 */
public final class CursorAnimation {
	private final double centerX;
	private final double centerY;
	private final double radiusX;
	private final double radiusY;
	private final double targetRadius;
	private final double startAngle;
	private final double endAngle;
	private final double targetX;
	private final double targetY;
	private final long startNanos;
	private final long durationNanos;

	private CursorAnimation(double centerX, double centerY, double radiusX, double radiusY,
			double targetRadius, double startAngle, double endAngle, double targetX, double targetY,
			long startNanos, long durationNanos) {
		this.centerX = centerX;
		this.centerY = centerY;
		this.radiusX = radiusX;
		this.radiusY = radiusY;
		this.targetRadius = targetRadius;
		this.startAngle = startAngle;
		this.endAngle = endAngle;
		this.targetX = targetX;
		this.targetY = targetY;
		this.startNanos = startNanos;
		this.durationNanos = Math.max(1L, durationNanos);
	}

	public static CursorAnimation spiral(double centerX, double centerY, double targetX, double targetY,
			double radiusX, double radiusY, double turns, long startNanos, long durationNanos) {
		double normalizedX = radiusX <= 1.0E-4 ? 0.0 : (targetX - centerX) / radiusX;
		double normalizedY = radiusY <= 1.0E-4 ? 0.0 : (targetY - centerY) / radiusY;
		double targetRadius = Math.hypot(normalizedX, normalizedY);
		double endAngle = Math.atan2(normalizedY, normalizedX);
		double startAngle = endAngle - Math.PI * 2.0 * turns;

		return new CursorAnimation(centerX, centerY, radiusX, radiusY, targetRadius, startAngle,
				endAngle, targetX, targetY, startNanos, durationNanos);
	}

	public double getX(long nowNanos) {
		double t = ease(progress(nowNanos));
		double angle = this.startAngle + (this.endAngle - this.startAngle) * t;
		double radius = this.targetRadius * t;

		return this.centerX + this.radiusX * radius * Math.cos(angle);
	}

	public double getY(long nowNanos) {
		double t = ease(progress(nowNanos));
		double angle = this.startAngle + (this.endAngle - this.startAngle) * t;
		double radius = this.targetRadius * t;

		return this.centerY + this.radiusY * radius * Math.sin(angle);
	}

	public double getEndX() {
		return this.targetX;
	}

	public double getEndY() {
		return this.targetY;
	}

	public long getEndNanos() {
		return this.startNanos + this.durationNanos;
	}

	private double progress(long nowNanos) {
		double t = (nowNanos - this.startNanos) / (double) this.durationNanos;

		if (t <= 0.0) {
			return 0.0;
		}

		if (t >= 1.0) {
			return 1.0;
		}

		return t;
	}

	private static double ease(double t) {
		return t * t * (3.0 - 2.0 * t);
	}
}
