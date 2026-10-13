package com.activitywebhook;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigDefaultsTest
{
	@Test
	public void offByDefaultAndNothingConfigured()
	{
		ActivityWebhookConfig c = new ActivityWebhookConfig()
		{
		};
		assertFalse(c.enabled());
		assertEquals("", c.endpointUrl());
		assertEquals("", c.sharedSecret());
		assertEquals("", c.displayName());
		assertFalse(c.includeNpcName());
		assertFalse("in-game name is off by default", c.useInGameName());
		assertEquals(5, c.minIntervalSeconds());
		assertEquals(EventSender.MIN_INTERVAL_SECONDS, 5);
	}
}
