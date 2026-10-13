package com.activitywebhook;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThrottleTest
{
	private final EventFactory f = new EventFactory(new TestSupport.MutableClock(0));
	private final WebhookEvent.Activity wc = new WebhookEvent.Activity("skilling", "Woodcutting", null);
	private final WebhookEvent.Activity bank = new WebhookEvent.Activity("banking", null, null);
	private static final long MIN = 5_000;

	private Throttle.Decision d(Throttle t, long now, WebhookEvent.Activity a)
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
		t.onResult(t.gen(), 100, true);
		assertNull("unchanged state is not re-sent", d(t, 1_000, wc));
		assertNull("change inside min interval waits", d(t, 4_999, bank));
		Throttle.Decision second = d(t, 5_000, bank);
		assertEquals(Throttle.Kind.STATE, second.kind);
		assertEquals("banking", second.event.getActivity().getCategory());
		t.onResult(t.gen(), 5_000, true);
		assertNull(d(t, 30_000, bank));
	}

	@Test
	public void heartbeatEvery60sNotMoreOften()
	{
		Throttle t = new Throttle();
		d(t, 0, wc);
		t.onResult(t.gen(), 0, true);
		assertNull(d(t, 59_999, wc));
		Throttle.Decision hb = d(t, 60_000, wc);
		assertEquals(Throttle.Kind.HEARTBEAT, hb.kind);
		assertEquals("heartbeat", hb.event.getType());
		t.onResult(t.gen(), 60_000, true);
		assertNull(d(t, 119_999, wc));
		assertEquals(Throttle.Kind.HEARTBEAT, d(t, 120_000, wc).kind);
	}

	@Test
	public void oneShotsQueueAndRespectMinIntervalExceptLogout()
	{
		Throttle t = new Throttle();
		d(t, 0, wc);
		t.onResult(t.gen(), 0, true);
		t.queue(f.levelUp("M", "Woodcutting", 50));
		assertNull("level-up waits for the min interval", d(t, 1_000, wc));
		Throttle.Decision lv = d(t, 5_000, wc);
		assertEquals("level_up", lv.event.getType());
		t.onResult(t.gen(), 5_000, true);
		t.queue(f.logout("M"));
		assertEquals("logout skips the min interval", "logout", d(t, 5_001, wc).event.getType());
		for (int i = 0; i < 8; i++)
		{
			t.queue(f.levelUp("M", "Mining", i));
		}
		assertEquals(Throttle.MAX_QUEUE, t.queued());
	}

	@Test
	public void staleReplyAfterResetIsIgnoredAndNoOverlap()
	{
		Throttle t = new Throttle();
		Throttle.Decision old = d(t, 0, wc);
		t.reset();
		assertNull("old request still in flight: no second request", d(t, 10_000, wc));
		t.onResult(old.generation, 10_001, false);   // late failure from before the reset
		assertEquals("stale failure doesn't back off", 0, t.failures());
		assertTrue(t.backoffUntil() < 0);
		Throttle.Decision fresh = d(t, 10_002, wc);
		assertNotNull("fresh state sent after the old reply", fresh);
		assertNotEquals(old.generation, fresh.generation);
	}

	@Test
	public void queuedLoginDropsStaleLogout()
	{
		Throttle t = new Throttle();
		t.queue(f.logout("M"));
		t.drop("logout");
		assertEquals(0, t.queued());
	}

	@Test
	public void failedStateIsRetriedAfterExponentialBackoff()
	{
		Throttle t = new Throttle();
		d(t, 0, wc);
		t.onResult(t.gen(), 10, false);
		assertEquals(10 + 5_000, t.backoffUntil());
		assertNull(d(t, 5_009, wc));
		assertEquals("unsent state retried", Throttle.Kind.STATE, d(t, 5_010, wc).kind);
		t.onResult(t.gen(), 5_010, false);
		assertEquals(5_010 + 10_000, t.backoffUntil());
		for (int i = 0; i < 20; i++)
		{
			t.decide(Long.MAX_VALUE / 4, MIN, wc.key(), () -> f.state("M", wc), () -> f.heartbeat("M", wc));
			t.onResult(t.gen(), 0, false);
		}
		assertEquals("capped at 5 min", Throttle.BACKOFF_MAX_MS, t.backoffUntil());
		t.decide(Throttle.BACKOFF_MAX_MS, MIN, wc.key(), () -> f.state("M", wc), () -> f.heartbeat("M", wc));
		t.onResult(t.gen(), Throttle.BACKOFF_MAX_MS, true);
		assertEquals(0, t.failures());
	}
}
