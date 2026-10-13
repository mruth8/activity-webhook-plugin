package com.pixelpetpals;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Decides what (if anything) to send now. Pure and clock-free: callers pass "now" in ms.
 * - State events go out only when the state changes, never more often than minIntervalMs.
 * - A heartbeat goes out when nothing has been sent for HEARTBEAT_MS (60 s, per the v1 spec).
 * - One-shot events (level_up, login, logout) queue (max 5, oldest dropped) and also respect minIntervalMs,
 *   except "urgent" ones (logout), which only wait for backoff.
 * - One request in flight at a time. Failures back off exponentially: 5 s, 10 s, ... capped at 5 min.
 */
public class Throttle
{
	public static final long HEARTBEAT_MS = 60_000;
	public static final long BACKOFF_BASE_MS = 5_000;
	public static final long BACKOFF_MAX_MS = 300_000;
	public static final int MAX_QUEUE = 5;

	public enum Kind { STATE, ONE_SHOT, HEARTBEAT }

	public static final class Decision
	{
		public final Kind kind;
		public final PetEvent event;

		Decision(Kind kind, PetEvent event)
		{
			this.kind = kind;
			this.event = event;
		}
	}

	private final Deque<PetEvent> oneShots = new ArrayDeque<>();
	private String lastSentStateKey;
	private String pendingStateKey;
	private long lastSentAt = Long.MIN_VALUE / 2;
	private long backoffUntil = Long.MIN_VALUE / 2;
	private int failures;
	private boolean inFlight;
	private String inFlightStateKey;

	public synchronized void reset()
	{
		oneShots.clear();
		lastSentStateKey = null;
		pendingStateKey = null;
		lastSentAt = Long.MIN_VALUE / 2;
		backoffUntil = Long.MIN_VALUE / 2;
		failures = 0;
		inFlight = false;
		inFlightStateKey = null;
	}

	public synchronized void queue(PetEvent e)
	{
		if (oneShots.size() >= MAX_QUEUE)
		{
			oneShots.removeFirst();
		}
		oneShots.addLast(e);
	}

	/**
	 * @param stateKey key of the current state (activity), or null if unknown
	 * @param stateEvent builds the state event if it is chosen
	 * @param heartbeatEvent builds the heartbeat if it is chosen
	 */
	public synchronized Decision decide(long now, long minIntervalMs, String stateKey,
		java.util.function.Supplier<PetEvent> stateEvent, java.util.function.Supplier<PetEvent> heartbeatEvent)
	{
		pendingStateKey = stateKey;
		if (inFlight || now < backoffUntil)
		{
			return null;
		}
		boolean intervalOk = now - lastSentAt >= minIntervalMs;
		PetEvent head = oneShots.peekFirst();
		if (head != null && (intervalOk || "logout".equals(head.getType())))
		{
			oneShots.removeFirst();
			return start(now, null, new Decision(Kind.ONE_SHOT, head));
		}
		if (!intervalOk)
		{
			return null;
		}
		if (stateKey != null && !stateKey.equals(lastSentStateKey))
		{
			return start(now, stateKey, new Decision(Kind.STATE, stateEvent.get()));
		}
		if (now - lastSentAt >= HEARTBEAT_MS)
		{
			return start(now, null, new Decision(Kind.HEARTBEAT, heartbeatEvent.get()));
		}
		return null;
	}

	private Decision start(long now, String stateKey, Decision d)
	{
		inFlight = true;
		inFlightStateKey = stateKey;
		lastSentAt = now;
		return d;
	}

	public synchronized void onResult(long now, boolean ok)
	{
		inFlight = false;
		if (ok)
		{
			failures = 0;
			if (inFlightStateKey != null)
			{
				lastSentStateKey = inFlightStateKey;
			}
		}
		else
		{
			failures++;
			long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << Math.min(failures - 1, 16));
			backoffUntil = now + delay;
		}
		inFlightStateKey = null;
	}

	synchronized int failures()
	{
		return failures;
	}

	synchronized long backoffUntil()
	{
		return backoffUntil;
	}

	synchronized int queued()
	{
		return oneShots.size();
	}
}
