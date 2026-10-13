package com.activitywebhook;

import com.google.gson.Gson;
import java.io.IOException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Posts events with the injected OkHttpClient (async enqueue; no threads of our own). Fails quietly: debug log
 * plus backoff via {@link Throttle}. Sends nothing unless enabled, with a valid http(s) URL and a display name.
 */
@Slf4j
public class EventSender
{
	static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	static final String SECRET_HEADER = "X-Pet-Secret";

	public interface Settings
	{
		boolean enabled();

		String endpointUrl();

		String sharedSecret();

		String displayName();

		int minIntervalSeconds();

		/** "Use my in-game name". */
		boolean useInGameName();

		/** The local player's name as last read on the client thread, or null if unavailable. */
		String inGameName();
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final Clock clock;
	private final Throttle throttle = new Throttle();
	private final EventFactory factory;
	private boolean active;

	public EventSender(OkHttpClient http, Gson gson, Clock clock)
	{
		this.http = http;
		this.gson = gson;
		this.clock = clock;
		this.factory = new EventFactory(clock);
	}

	public EventFactory factory()
	{
		return factory;
	}

	Throttle throttle()
	{
		return throttle;
	}

	public static final int MIN_INTERVAL_SECONDS = 5;
	public static final int MAX_INTERVAL_SECONDS = 60;

	/**
	 * Parsed endpoint, or null if empty/invalid. https is required, except plain http to a loopback host
	 * (localhost, 127.0.0.1, ::1) for a local relay.
	 */
	public static HttpUrl endpoint(String url)
	{
		if (url == null || url.trim().isEmpty())
		{
			return null;
		}
		HttpUrl u = HttpUrl.parse(url.trim());
		if (u == null)
		{
			return null;
		}
		if ("https".equals(u.scheme()))
		{
			return u;
		}
		return "http".equals(u.scheme()) && isLoopback(u.host()) ? u : null;
	}

	static boolean isLoopback(String host)
	{
		return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
	}

	/** Min interval in ms, clamped to 5-60 s whatever the config says. */
	static long minIntervalMs(int seconds)
	{
		return Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, seconds)) * 1000L;
	}

	/** Player to send as, or null when sending is off (disabled / no valid URL / no display name, or in-game name on but unavailable). */
	public static String activePlayer(Settings s)
	{
		if (!s.enabled() || endpoint(s.endpointUrl()) == null)
		{
			return null;
		}
		return EventFactory.cleanPlayer(s.useInGameName() ? s.inGameName() : s.displayName());
	}

	public void queueLevelUp(Settings s, String skill, int level)
	{
		String p = ensureActive(s);
		if (p != null)
		{
			throttle.queue(factory.levelUp(p, skill, level));
		}
	}

	public void queueLogin(Settings s)
	{
		String p = ensureActive(s);
		if (p != null)
		{
			throttle.drop("logout");   // a logout that never got out is stale now: discard it
			throttle.queue(factory.login(p));
		}
	}

	public void queueLogout(Settings s)
	{
		String p = ensureActive(s);
		if (p != null)
		{
			throttle.queue(factory.logout(p));
		}
	}

	/**
	 * Tracks the sendable/not-sendable transition and returns the player to send as (null = not sendable).
	 * On becoming sendable the throttle is cleared BEFORE anything new is queued, so only events from the
	 * non-sendable period are dropped; an event queued right after the transition (e.g. the login on the tick the
	 * in-game name resolves, or a logout right after a config change) survives.
	 */
	String ensureActive(Settings s)
	{
		String player = activePlayer(s);
		if (player == null)
		{
			if (active)
			{
				throttle.reset();
			}
			active = false;
		}
		else if (!active)
		{
			throttle.reset();
			active = true;
		}
		return player;
	}

	public void reset()
	{
		throttle.reset();
		active = false;
	}

	/** Call once per game tick (and on logout). current may be null when not logged in. Returns true if a request was enqueued. */
	public boolean tick(Settings s, WebhookEvent.Activity current)
	{
		String player = ensureActive(s);
		if (player == null)
		{
			return false;
		}
		long now = clock.millis();
		long minMs = minIntervalMs(s.minIntervalSeconds());
		Throttle.Decision d = throttle.decide(now, minMs, current == null ? null : current.key(),
			() -> factory.state(player, current),
			() -> factory.heartbeat(player, current));
		if (d == null)
		{
			return false;
		}
		post(endpoint(s.endpointUrl()), s.sharedSecret(), d.event, d.generation);
		return true;
	}

	private void post(HttpUrl url, String secret, WebhookEvent e, int gen)
	{
		Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(e)));
		if (secret != null && !secret.isEmpty())
		{
			rb.header(SECRET_HEADER, secret);
		}
		try
		{
			http.newCall(rb.build()).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					log.debug("activity webhook: send failed: {}", ex.toString());
					throttle.onResult(gen, clock.millis(), false);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					try (Response r = response)
					{
						boolean ok = r.isSuccessful();
						if (!ok)
						{
							log.debug("activity webhook: endpoint answered HTTP {}", r.code());
						}
						throttle.onResult(gen, clock.millis(), ok);
					}
				}
			});
		}
		catch (RuntimeException ex)
		{
			log.debug("activity webhook: could not enqueue: {}", ex.toString());
			throttle.onResult(gen, clock.millis(), false);
		}
	}
}
