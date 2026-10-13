package com.pixelpetpals;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.time.Clock;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;

/**
 * Pixel Pet Pals: sends the local player's own activity (event format v1) to a user-chosen endpoint.
 * Off by default. Reads only: own stats (StatChanged), own animation, own interacting target if it is an NPC,
 * and whether the bank interface is open. Never reads chat, other players, location or the account name.
 */
@Slf4j
@PluginDescriptor(
	name = "Pixel Pet Pals",
	description = "Drives a pixel pet stream widget from your own activity. Sends data to a server you choose (third party). Off by default.",
	tags = {"pet", "widget", "stream", "overlay", "third-party"}
)
public class PixelPetPalsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private PixelPetPalsConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private final ActivityTracker tracker = new ActivityTracker();
	private EventSender sender;
	private boolean loggedIn;

	private final EventSender.Settings settings = new EventSender.Settings()
	{
		public boolean enabled() { return config.enabled(); }

		public String endpointUrl() { return config.endpointUrl(); }

		public String sharedSecret() { return config.sharedSecret(); }

		public String displayName() { return config.displayName(); }

		public int minIntervalSeconds() { return config.minIntervalSeconds(); }
	};

	@Override
	protected void startUp()
	{
		sender = new EventSender(okHttpClient, gson, Clock.systemUTC());
		tracker.reset(client.getTickCount());
		loggedIn = client.getGameState() == GameState.LOGGED_IN;
	}

	@Override
	protected void shutDown()
	{
		sender.reset();
		sender = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState s = e.getGameState();
		if (s == GameState.LOGGING_IN || s == GameState.HOPPING)
		{
			tracker.reset(client.getTickCount());
		}
		else if (s == GameState.LOGGED_IN && !loggedIn)
		{
			loggedIn = true;
			sender.queueLogin(settings);
		}
		else if (s == GameState.LOGIN_SCREEN && loggedIn)
		{
			loggedIn = false;
			tracker.reset(client.getTickCount());
			sender.queueLogout(settings);
			sender.tick(settings, null);   // no game ticks at the login screen; logout skips the min interval
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		// StatChanged is only ever about the local player's own stats.
		int level = tracker.onStat(e.getSkill(), e.getXp(), e.getLevel(), client.getTickCount());
		if (level > 0)
		{
			sender.queueLevelUp(settings, e.getSkill().getName(), level);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			tracker.setBankOpen(true);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			tracker.setBankOpen(false);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (PixelPetPalsConfig.GROUP.equals(e.getGroup()) && sender != null)
		{
			sender.reset();   // re-send current state to the (possibly new) endpoint
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		Actor target = me.getInteracting();
		String npcName = target instanceof NPC ? target.getName() : null;   // other players are ignored
		PetEvent.Activity a = tracker.tick(client.getTickCount(), me.getAnimation() != -1,
			npcName == null ? null : npcName, config.includeNpcName());
		sender.tick(settings, a);
	}

	@Provides
	PixelPetPalsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PixelPetPalsConfig.class);
	}
}
