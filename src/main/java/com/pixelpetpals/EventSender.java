package com.pixelpetpals;

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
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final Clock clock;
	private final Throttle throttle = new Throttle();
	private final EventFactory factory;

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

	/** Parsed endpoint, or null if empty/invalid/not http(s). */
	public static HttpUrl endpoint(String url)
	{
		if (url == null || url.trim().isEmpty())
		{
			return null;
		}
		HttpUrl u = HttpUrl.parse(url.trim());
		return u != null && ("http".equals(u.scheme()) || "https".equals(u.scheme())) ? u : null;
	}

	/** Player to send as, or null when sending is off (disabled / no valid URL / no display name). */
	public static String activePlayer(Settings s)
	{
		if (!s.enabled() || endpoint(s.endpointUrl()) == null)
		{
			return null;
		}
		return EventFactory.cleanPlayer(s.displayName());
	}

	public void queueLevelUp(Settings s, String skill, int level)
	{
		String p = activePlayer(s);
		if (p != null)
		{
			throttle.queue(factory.levelUp(p, skill, level));
		}
	}

	public void queueLogin(Settings s)
	{
		String p = activePlayer(s);
		if (p != null)
		{
			throttle.queue(factory.login(p));
		}
	}

	public void queueLogout(Settings s)
	{
		String p = activePlayer(s);
		if (p != null)
		{
			throttle.queue(factory.logout(p));
		}
	}

	public void reset()
	{
		throttle.reset();
	}

	/** Call once per game tick (and on logout). current may be null when not logged in. Returns true if a request was enqueued. */
	public boolean tick(Settings s, PetEvent.Activity current)
	{
		String player = activePlayer(s);
		if (player == null)
		{
			throttle.reset();
			return false;
		}
		long now = clock.millis();
		long minMs = Math.max(2, Math.min(60, s.minIntervalSeconds())) * 1000L;
		Throttle.Decision d = throttle.decide(now, minMs, current == null ? null : current.key(),
			() -> factory.state(player, current),
			() -> factory.heartbeat(player, current));
		if (d == null)
		{
			return false;
		}
		post(endpoint(s.endpointUrl()), s.sharedSecret(), d.event);
		return true;
	}

	private void post(HttpUrl url, String secret, PetEvent e)
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
					log.debug("pixel pet pals: send failed: {}", ex.toString());
					throttle.onResult(clock.millis(), false);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					try (Response r = response)
					{
						boolean ok = r.isSuccessful();
						if (!ok)
						{
							log.debug("pixel pet pals: endpoint answered HTTP {}", r.code());
						}
						throttle.onResult(clock.millis(), ok);
					}
				}
			});
		}
		catch (RuntimeException ex)
		{
			log.debug("pixel pet pals: could not enqueue: {}", ex.toString());
			throttle.onResult(clock.millis(), false);
		}
	}
}
