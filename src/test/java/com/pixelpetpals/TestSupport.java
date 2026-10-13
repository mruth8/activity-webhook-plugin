package com.pixelpetpals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class TestSupport
{
	static final class MutableClock extends Clock
	{
		long ms;

		MutableClock(long ms)
		{
			this.ms = ms;
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return Instant.ofEpochMilli(ms);
		}

		@Override
		public long millis()
		{
			return ms;
		}
	}

	static final class Settings implements EventSender.Settings
	{
		boolean enabled = true;
		String url = "";
		String secret = "";
		String name = "Matthew";
		int minInterval = 5;

		public boolean enabled() { return enabled; }

		public String endpointUrl() { return url; }

		public String sharedSecret() { return secret; }

		public String displayName() { return name; }

		public int minIntervalSeconds() { return minInterval; }
	}

	private TestSupport()
	{
	}
}
