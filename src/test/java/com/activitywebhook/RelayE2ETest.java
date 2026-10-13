package com.activitywebhook;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * End-to-end: posts plugin-built events to the real relay (node relay/server.js), which validates them against
 * event format v1. Skipped unless PPP_RELAY_URL is set; scripts/e2e.sh starts the relay and sets it.
 */
public class RelayE2ETest
{
	@Test
	public void relayAcceptsPluginEvents() throws Exception
	{
		String url = System.getenv("PPP_RELAY_URL");
		Assume.assumeTrue("PPP_RELAY_URL not set", url != null && !url.isEmpty());
		String secret = System.getenv("PPP_RELAY_SECRET");
		OkHttpClient http = new OkHttpClient();
		Gson gson = new Gson();
		EventFactory f = new EventFactory(java.time.Clock.systemUTC());
		List<WebhookEvent> events = Arrays.asList(
			f.login("Matthew"),
			f.state("Matthew", new WebhookEvent.Activity("skilling", "Woodcutting", null)),
			f.state("Matthew", new WebhookEvent.Activity("skilling", "Mining", null)),
			f.state("Matthew", new WebhookEvent.Activity("skilling", "Fishing", null)),
			f.state("Matthew", new WebhookEvent.Activity("combat", null, null)),
			f.state("Matthew", new WebhookEvent.Activity("combat", null, "Hill Giant")),
			f.state("Matthew", new WebhookEvent.Activity("banking", null, null)),
			f.levelUp("Matthew", "Woodcutting", 70),
			f.heartbeat("Matthew", new WebhookEvent.Activity("banking", null, null)),
			f.state("Matthew", ActivityTracker.IDLE),
			f.logout("Matthew"));
		for (WebhookEvent e : events)
		{
			String body = gson.toJson(e);
			Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(MediaType.parse("application/json"), body));
			if (secret != null && !secret.isEmpty())
			{
				rb.header("X-Pet-Secret", secret);
			}
			try (Response r = http.newCall(rb.build()).execute())
			{
				String resp = r.body() == null ? "" : r.body().string();
				System.out.println("relay " + r.code() + " <- " + body);
				assertTrue("relay rejected " + body + ": " + r.code() + " " + resp, r.isSuccessful());
			}
		}
		if (secret != null && !secret.isEmpty())
		{
			Request bad = new Request.Builder().url(url).header("X-Pet-Secret", "wrong")
				.post(RequestBody.create(MediaType.parse("application/json"), gson.toJson(f.login("Matthew")))).build();
			try (Response r = http.newCall(bad).execute())
			{
				assertEquals("wrong secret must be refused", 401, r.code());
			}
		}
		// And a deliberately invalid event is refused, proving the relay really validates.
		Request invalid = new Request.Builder().url(url).header("X-Pet-Secret", secret == null ? "" : secret)
			.post(RequestBody.create(MediaType.parse("application/json"), "{\"v\":1,\"player\":\"M\",\"ts\":\"x\",\"type\":\"activity\",\"activity\":{\"category\":\"skilling\",\"region\":\"Varrock\",\"world\":1}}")).build();
		try (Response r = http.newCall(invalid).execute())
		{
			assertFalse("relay should reject invalid events", r.isSuccessful());
		}
	}
}
