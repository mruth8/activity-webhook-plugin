package com.activitywebhook;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventSenderTest
{
	private MockWebServer server;
	private final TestSupport.MutableClock clock = new TestSupport.MutableClock(1_000_000);
	private final TestSupport.Settings s = new TestSupport.Settings();
	private EventSender sender;
	private final WebhookEvent.Activity wc = new WebhookEvent.Activity("skilling", "Woodcutting", null);

	@Before
	public void up() throws Exception
	{
		server = new MockWebServer();
		server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
		sender = new EventSender(new OkHttpClient(), new Gson(), clock);
		s.url = "http://127.0.0.1:" + server.getPort() + "/events";
	}

	@After
	public void down() throws Exception
	{
		server.shutdown();
	}

	private void assertNothingSent() throws Exception
	{
		assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void disabledByDefaultSendsNothing() throws Exception
	{
		s.enabled = false;
		sender.queueLevelUp(s, "Mining", 50);
		assertFalse(sender.tick(s, wc));
		assertNothingSent();
	}

	@Test
	public void emptyOrInvalidUrlOrNoNameSendsNothing() throws Exception
	{
		for (String bad : new String[]{"", "   ", "ftp://example.com/x", "not a url", "file:///etc/passwd", "javascript:alert(1)",
			"http://pets.example.com/events", "http://192.168.1.5:8787/events", "http://127.0.0.2/events", "http://localhost.evil.com/events"})
		{
			s.url = bad;
			assertNull(EventSender.endpoint(bad));
			assertFalse(sender.tick(s, wc));
		}
		s.url = "http://127.0.0.1:" + server.getPort() + "/events";
		s.name = " ";
		assertFalse(sender.tick(s, wc));
		assertNothingSent();
		assertNotNull(EventSender.endpoint("https://pets.example.com/events"));
	}

	@Test
	public void httpsRequiredExceptLoopback()
	{
		for (String ok : new String[]{"https://pets.example.com/events", "https://192.168.1.5/events", "http://localhost:8787/events",
			"http://LOCALHOST/events", "http://127.0.0.1:8787/events", "http://[::1]:8787/events"})
		{
			assertNotNull(ok, EventSender.endpoint(ok));
		}
		assertNull(EventSender.endpoint("http://example.com/events"));
		assertNull(EventSender.endpoint("http://10.0.0.2:8787/events"));
	}

	@Test
	public void minIntervalClampedTo5s()
	{
		assertEquals(5_000, EventSender.minIntervalMs(0));
		assertEquals(5_000, EventSender.minIntervalMs(2));
		assertEquals(5_000, EventSender.minIntervalMs(5));
		assertEquals(7_000, EventSender.minIntervalMs(7));
		assertEquals(60_000, EventSender.minIntervalMs(600));
	}

	@Test
	public void minIntervalOf2IsEnforcedAs5OnTheWire() throws Exception
	{
		s.minInterval = 2;
		server.enqueue(new MockResponse().setResponseCode(204));
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue(sender.tick(s, wc));
		server.takeRequest(5, TimeUnit.SECONDS);
		waitIdle();
		clock.ms += 3_000;
		assertFalse("3 s later: still inside 5 s", sender.tick(s, new WebhookEvent.Activity("banking", null, null)));
		clock.ms += 2_000;
		assertTrue(sender.tick(s, new WebhookEvent.Activity("banking", null, null)));
	}

	@Test
	public void eventsCreatedWhileDisabledAreDroppedOnEnable() throws Exception
	{
		s.enabled = false;
		sender.queueLogin(s);
		sender.queueLevelUp(s, "Mining", 50);
		assertFalse(sender.tick(s, wc));
		// Also something queued while active, then disabled: must not survive the off period.
		s.enabled = true;
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue(sender.tick(s, wc));
		server.takeRequest(5, TimeUnit.SECONDS);
		waitIdle();
		sender.queueLevelUp(s, "Fishing", 60);
		s.enabled = false;
		assertFalse(sender.tick(s, wc));
		clock.ms += 3_600_000;
		s.enabled = true;
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue(sender.tick(s, wc));
		RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
		String body = r.getBody().readUtf8();
		assertTrue("fresh current state, not a stale queued event: " + body, body.contains("\"type\":\"activity\"") && body.contains("Woodcutting"));
		waitIdle();
		clock.ms += 10_000;
		assertFalse("nothing else queued", sender.tick(s, wc));
		assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void loginDiscardsBlockedLogout() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500));
		assertTrue(sender.tick(s, wc));
		server.takeRequest(5, TimeUnit.SECONDS);
		waitIdle();
		sender.queueLogout(s);
		assertFalse("logout blocked by backoff", sender.tick(s, null));
		clock.ms += 600_000;
		sender.queueLogin(s);
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue(sender.tick(s, wc));
		String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
		assertTrue("login goes out, stale logout discarded: " + body, body.contains("\"type\":\"login\""));
		waitIdle();
		assertEquals(0, sender.throttle().queued());
	}

	private void waitIdle() throws InterruptedException
	{
		long t0 = System.currentTimeMillis();
		while (sender.throttle().inFlight() && System.currentTimeMillis() - t0 < 5000)
		{
			Thread.sleep(5);
		}
		assertFalse(sender.throttle().inFlight());
	}

	@Test
	public void postsJsonWithSecretHeader() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(204));
		s.secret = "hunter2";
		assertTrue(sender.tick(s, wc));
		RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
		assertEquals("POST", r.getMethod());
		assertEquals("/events", r.getPath());
		assertEquals("hunter2", r.getHeader("X-Pet-Secret"));
		assertTrue(r.getHeader("Content-Type").startsWith("application/json"));
		JsonObject o = new JsonParser().parse(r.getBody().readUtf8()).getAsJsonObject();
		EventFactoryTest.assertValidV1(o);
		assertEquals("Matthew", o.get("player").getAsString());
		assertEquals("Woodcutting", o.getAsJsonObject("activity").get("skill").getAsString());
	}

	@Test
	public void noSecretMeansNoHeader() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue(sender.tick(s, wc));
		RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
		assertNull(r.getHeader("X-Pet-Secret"));
	}

	@Test
	public void errorBacksOffQuietly() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500));
		assertTrue(sender.tick(s, wc));
		server.takeRequest(5, TimeUnit.SECONDS);
		long t0 = System.currentTimeMillis();
		while (sender.throttle().failures() == 0 && System.currentTimeMillis() - t0 < 5000)
		{
			Thread.sleep(10);
		}
		assertEquals(1, sender.throttle().failures());
		waitIdle();
		clock.ms += 4_000;
		assertFalse("still backing off", sender.tick(s, wc));
		clock.ms += 1_500;
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue("retries after backoff", sender.tick(s, wc));
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
	}

	@Test
	public void unreachableEndpointFailsQuietly() throws Exception
	{
		s.url = "http://127.0.0.1:1/events";
		assertTrue(sender.tick(s, wc));
		long t0 = System.currentTimeMillis();
		while (sender.throttle().failures() == 0 && System.currentTimeMillis() - t0 < 5000)
		{
			Thread.sleep(10);
		}
		assertEquals(1, sender.throttle().failures());
	}

	private String onlyRequestType() throws Exception
	{
		RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(r);
		String type = new JsonParser().parse(r.getBody().readUtf8()).getAsJsonObject().get("type").getAsString();
		waitIdle();
		assertNull("nothing else sent", server.takeRequest(300, TimeUnit.MILLISECONDS));
		return type;
	}

	@Test
	public void logoutRightAfterConfigChangeIsSentNotHeartbeat() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue(sender.tick(s, wc));
		server.takeRequest(5, TimeUnit.SECONDS);
		waitIdle();
		clock.ms += 1_000;
		sender.reset();   // onConfigChanged
		server.enqueue(new MockResponse().setResponseCode(204));
		sender.queueLogout(s);   // GameState LOGIN_SCREEN, no tick in between
		assertTrue(sender.tick(s, null));
		assertEquals("logout", onlyRequestType());
	}

	@Test
	public void logoutRightAfterPluginStartIsSent() throws Exception
	{
		EventSender fresh = new EventSender(new OkHttpClient(), new Gson(), clock);   // startUp
		sender = fresh;
		server.enqueue(new MockResponse().setResponseCode(204));
		fresh.queueLogout(s);
		assertTrue(fresh.tick(s, null));
		assertEquals("logout", onlyRequestType());
	}
}
