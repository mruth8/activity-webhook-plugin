package com.pixelpetpals;

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

	public PetEvent state(String player, PetEvent.Activity a)
	{
		String type = "idle".equals(a.getCategory()) ? "idle" : "activity";
		return new PetEvent(player, now(), type, a, null);
	}

	public PetEvent heartbeat(String player, PetEvent.Activity current)
	{
		return new PetEvent(player, now(), "heartbeat", current, null);
	}

	public PetEvent levelUp(String player, String skill, int level)
	{
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("skill", skill);
		d.put("level", level);
		return new PetEvent(player, now(), "level_up", null, d);
	}

	public PetEvent login(String player)
	{
		return new PetEvent(player, now(), "login", null, null);
	}

	public PetEvent logout(String player)
	{
		return new PetEvent(player, now(), "logout", null, null);
	}
}
