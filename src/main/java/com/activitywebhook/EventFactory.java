package com.activitywebhook;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds v1 events. Pure: no game or network access. */
public final class EventFactory
{
	public static final int MAX_PLAYER_LEN = 64;

	private final Clock clock;

	public EventFactory(Clock clock)
	{
		this.clock = clock;
	}

	/** Trimmed display name clipped to 64 chars, or null if blank (=> nothing is sent). */
	public static String cleanPlayer(String name)
	{
		if (name == null)
		{
			return null;
		}
		String s = name.trim();
		if (s.isEmpty())
		{
			return null;
		}
		return s.length() > MAX_PLAYER_LEN ? s.substring(0, MAX_PLAYER_LEN) : s;
	}

	private String now()
	{
		return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
	}

	public WebhookEvent state(String player, WebhookEvent.Activity a)
	{
		String type = "idle".equals(a.getCategory()) ? "idle" : "activity";
		return new WebhookEvent(player, now(), type, a, null);
	}

	public WebhookEvent heartbeat(String player, WebhookEvent.Activity current)
	{
		return new WebhookEvent(player, now(), "heartbeat", current, null);
	}

	public WebhookEvent levelUp(String player, String skill, int level)
	{
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("skill", skill);
		d.put("level", level);
		return new WebhookEvent(player, now(), "level_up", null, d);
	}

	public WebhookEvent login(String player)
	{
		return new WebhookEvent(player, now(), "login", null, null);
	}

	public WebhookEvent logout(String player)
	{
		return new WebhookEvent(player, now(), "logout", null, null);
	}
}
