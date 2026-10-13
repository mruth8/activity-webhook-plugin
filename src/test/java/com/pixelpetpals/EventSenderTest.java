package com.pixelpetpals;

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
	private final PetEvent.Activity wc = new PetEvent.Activity("skilling", "Woodcutting", null);

	@Before
	public void up() throws Exception
	{
		server = new MockWebServer();
		server.start();
		sender = new EventSender(new OkHttpClient(), new Gson(), clock);
		s.url = server.url("/events").toString();
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
		for (String bad : new String[]{"", "   ", "ftp://example.com/x", "not a url", "file:///etc/passwd", "javascript:alert(1)"})
		{
			s.url = bad;
			assertNull(EventSender.endpoint(bad));
			assertFalse(sender.tick(s, wc));
		}
		s.url = server.url("/events").toString();
		s.name = " ";
		assertFalse(sender.tick(s, wc));
		assertNothingSent();
		assertNotNull(EventSender.endpoint("https://pets.example.com/events"));
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
}
