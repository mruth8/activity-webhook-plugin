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
 * and whether the bank interface is open. Never reads chat, other players or location. The local player's own name
 * is read only when "Use my in-game name" is on (it is used for nothing else).
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

		public boolean useInGameName() { return config.useInGameName(); }

		public String inGameName() { return config.useInGameName() ? inGameName : null; }
	};

	/** Local player's name, read on the client thread once logged in; null until then (and after logout/hop). */
	private volatile String inGameName;
	/** Login seen; the login event is queued on the first tick where the player name can be resolved. */
	private boolean loginPending;

	/**
	 * Name to cache after this tick: null when the option is off (getName() is then never called), the cached
	 * value when already known, else a fresh null-safe read.
	 */
	static String cachedName(boolean useInGameName, String cached, Player me)
	{
		if (!useInGameName)
		{
			return null;
		}
		return cached != null ? cached : readName(me);
	}

	/** Null-safe read of the local player's own name. */
	static String readName(Player me)
	{
		if (me == null)
		{
			return null;
		}
		String n = me.getName();
		return n == null || n.trim().isEmpty() ? null : n;
	}

	@Override
	protected void startUp()
	{
		sender = new EventSender(okHttpClient, gson, Clock.systemUTC());
		tracker.reset(client.getTickCount());
		loggedIn = client.getGameState() == GameState.LOGGED_IN;
		inGameName = null;   // read on the next game tick (client thread)
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
			inGameName = null;   // re-read after login / world hop
		}
		else if (s == GameState.LOGGED_IN && !loggedIn)
		{
			loggedIn = true;
			loginPending = true;
		}
		else if (s == GameState.LOGIN_SCREEN && loggedIn)
		{
			loggedIn = false;
			tracker.reset(client.getTickCount());
			loginPending = false;
			sender.queueLogout(settings);   // still uses the name read while logged in
			sender.tick(settings, null);   // no game ticks at the login screen; logout skips the min interval
			inGameName = null;
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
			// Off: forget the name. On: it is re-read on the next game tick (client thread).
			inGameName = null;
			sender.reset();   // drop anything queued; re-send fresh current state to the (possibly new) endpoint
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		// GameTick runs on the client thread, only while logged in: the right place to read the local player.
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		// Only read our own name when the user opted in to sending it.
		inGameName = cachedName(config.useInGameName(), inGameName, me);
		if (loginPending)
		{
			if (!config.enabled())
			{
				loginPending = false;   // logged in while sending was off: no stale login later
			}
			else if (EventSender.activePlayer(settings) != null)
			{
				loginPending = false;
				sender.queueLogin(settings);
			}
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
