package com.pixelpetpals;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigDefaultsTest
{
	@Test
	public void offByDefaultAndNothingConfigured()
	{
		PixelPetPalsConfig c = new PixelPetPalsConfig()
		{
		};
		assertFalse(c.enabled());
		assertEquals("", c.endpointUrl());
		assertEquals("", c.sharedSecret());
		assertEquals("", c.displayName());
		assertFalse(c.includeNpcName());
		assertEquals(5, c.minIntervalSeconds());
	}
}
