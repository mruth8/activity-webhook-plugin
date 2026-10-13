package com.pixelpetpals;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(PixelPetPalsConfig.GROUP)
public interface PixelPetPalsConfig extends Config
{
	String GROUP = "pixelpetpals";

	@ConfigSection(
		name = "Data sharing (third party)",
		description = "WARNING: when enabled, this plugin sends your activity (skilling/combat/banking/idle state and level-ups) "
			+ "to a server YOU choose. That server is not controlled or verified by Jagex or RuneLite. "
			+ "Only enable it with an endpoint you trust. Nothing is sent while disabled or with no endpoint set.",
		position = 0
	)
	String sharingSection = "sharing";

	@ConfigItem(
		keyName = "enabled",
		name = "Send activity",
		description = "Off by default. When on, sends your own activity to the endpoint below (a server not controlled by Jagex or RuneLite).",
		section = sharingSection,
		position = 0
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "endpointUrl",
		name = "Endpoint URL",
		description = "http(s) URL that receives the events, e.g. http://127.0.0.1:8787/events. Empty = send nothing.",
		section = sharingSection,
		position = 1
	)
	default String endpointUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sharedSecret",
		name = "Shared secret",
		description = "Optional. Sent as the X-Pet-Secret header; must match the relay's PET_SECRET.",
		secret = true,
		section = sharingSection,
		position = 2
	)
	default String sharedSecret()
	{
		return "";
	}

	@ConfigItem(
		keyName = "displayName",
		name = "Display name",
		description = "Name the widget shows (event field 'player'). Type any alias; it is NOT read from the game. Empty = send nothing.",
		section = sharingSection,
		position = 3
	)
	default String displayName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "useInGameName",
		name = "Use my in-game name",
		description = "Off by default. When on, the 'player' field is your RuneScape name, read from the game, so your "
			+ "RuneScape name is sent to your chosen server (not controlled by Jagex or RuneLite). When off, the Display name above is used.",
		section = sharingSection,
		position = 4
	)
	default boolean useInGameName()
	{
		return false;
	}

	@ConfigItem(
		keyName = "includeNpcName",
		name = "Include NPC name in combat",
		description = "Off by default. When on, combat events include the name of the NPC you are fighting. Other players are never sent.",
		section = sharingSection,
		position = 5
	)
	default boolean includeNpcName()
	{
		return false;
	}

	@Range(min = 2, max = 60)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "minIntervalSeconds",
		name = "Minimum interval",
		description = "Never send more often than this (2-60 s). A heartbeat is sent at most every 60 s.",
		section = sharingSection,
		position = 6
	)
	default int minIntervalSeconds()
	{
		return 5;
	}
}
