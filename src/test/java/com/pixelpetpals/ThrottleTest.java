package com.pixelpetpals;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThrottleTest
{
	private final EventFactory f = new EventFactory(new TestSupport.MutableClock(0));
	private final PetEvent.Activity wc = new PetEvent.Activity("skilling", "Woodcutting", null);
	private final PetEvent.Activity bank = new PetEvent.Activity("banking", null, null);
	private static final long MIN = 5_000;

	private Throttle.Decision d(Throttle t, long now, PetEvent.Activity a)
	{
		return t.decide(now, MIN, a.key(), () -> f.state("M", a), () -> f.heartbeat("M", a));
	}

	@Test
	public void sendsOnChangeOnlyAndRespectsMinInterval()
	{
		Throttle t = new Throttle();
		Throttle.Decision first = d(t, 0, wc);
		assertEquals(Throttle.Kind.STATE, first.kind);
		assertNull("one in flight at a time", d(t, 100, bank));
		t.onResult(100, true);
		assertNull("unchanged state is not re-sent", d(t, 1_000, wc));
		assertNull("change inside min interval waits", d(t, 4_999, bank));
		Throttle.Decision second = d(t, 5_000, bank);
		assertEquals(Throttle.Kind.STATE, second.kind);
		assertEquals("banking", second.event.getActivity().getCategory());
		t.onResult(5_000, true);
		assertNull(d(t, 30_000, bank));
	}

	@Test
	public void heartbeatEvery60sNotMoreOften()
	{
		Throttle t = new Throttle();
		d(t, 0, wc);
		t.onResult(0, true);
		assertNull(d(t, 59_999, wc));
		Throttle.Decision hb = d(t, 60_000, wc);
		assertEquals(Throttle.Kind.HEARTBEAT, hb.kind);
		assertEquals("heartbeat", hb.event.getType());
		t.onResult(60_000, true);
		assertNull(d(t, 119_999, wc));
		assertEquals(Throttle.Kind.HEARTBEAT, d(t, 120_000, wc).kind);
	}

	@Test
	public void oneShotsQueueAndRespectMinIntervalExceptLogout()
	{
		Throttle t = new Throttle();
		d(t, 0, wc);
		t.onResult(0, true);
		t.queue(f.levelUp("M", "Woodcutting", 50));
		assertNull("level-up waits for the min interval", d(t, 1_000, wc));
		Throttle.Decision lv = d(t, 5_000, wc);
		assertEquals("level_up", lv.event.getType());
		t.onResult(5_000, true);
		t.queue(f.logout("M"));
		assertEquals("logout skips the min interval", "logout", d(t, 5_001, wc).event.getType());
		for (int i = 0; i < 8; i++)
		{
			t.queue(f.levelUp("M", "Mining", i));
		}
		assertEquals(Throttle.MAX_QUEUE, t.queued());
	}

	@Test
	public void failedStateIsRetriedAfterExponentialBackoff()
	{
		Throttle t = new Throttle();
		d(t, 0, wc);
		t.onResult(10, false);
		assertEquals(10 + 5_000, t.backoffUntil());
		assertNull(d(t, 5_009, wc));
		assertEquals("unsent state retried", Throttle.Kind.STATE, d(t, 5_010, wc).kind);
		t.onResult(5_010, false);
		assertEquals(5_010 + 10_000, t.backoffUntil());
		for (int i = 0; i < 20; i++)
		{
			t.decide(Long.MAX_VALUE / 4, MIN, wc.key(), () -> f.state("M", wc), () -> f.heartbeat("M", wc));
			t.onResult(0, false);
		}
		assertEquals("capped at 5 min", Throttle.BACKOFF_MAX_MS, t.backoffUntil());
		t.decide(Throttle.BACKOFF_MAX_MS, MIN, wc.key(), () -> f.state("M", wc), () -> f.heartbeat("M", wc));
		t.onResult(Throttle.BACKOFF_MAX_MS, true);
		assertEquals(0, t.failures());
	}
}
