package com.pixelpetpals;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Player;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** "Use my in-game name": off = typed Display name, on = local player's name, on + unavailable = send nothing. */
public class PlayerNameTest
{
	private MockWebServer server;
	private final TestSupport.Settings s = new TestSupport.Settings();
	private EventSender sender;
	private final PetEvent.Activity wc = new PetEvent.Activity("skilling", "Woodcutting", null);

	/** Test-only stand-in for the game's Player (only getName is used). */
	static Player player(String name)
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(p, m, a) -> "getName".equals(m.getName()) ? name : null);
	}

	@Before
	public void up() throws Exception
	{
		server = new MockWebServer();
		server.start();
		sender = new EventSender(new OkHttpClient(), new Gson(), new TestSupport.MutableClock(1_000_000));
		s.url = server.url("/events").toString();
		s.name = "TypedAlias";
	}

	@After
	public void down() throws Exception
	{
		server.shutdown();
	}

	private String sentPlayer() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(204));
		assertTrue(sender.tick(s, wc));
		RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
		return new JsonParser().parse(r.getBody().readUtf8()).getAsJsonObject().get("player").getAsString();
	}

	@Test
	public void defaultIsOff()
	{
		assertFalse(new PixelPetPalsConfig() { }.useInGameName());
	}

	@Test
	public void offUsesTypedNameEvenWhenInGameNameKnown() throws Exception
	{
		s.useInGame = false;
		s.inGame = "Zezima";
		assertEquals("TypedAlias", EventSender.activePlayer(s));
		assertEquals("TypedAlias", sentPlayer());
	}

	@Test
	public void onUsesInGameName() throws Exception
	{
		s.useInGame = true;
		s.inGame = PixelPetPalsPlugin.readName(player("Zezima"));
		assertEquals("Zezima", sentPlayer());
	}

	@Test
	public void onWithBlankTypedNameStillSends() throws Exception
	{
		s.useInGame = true;
		s.name = "";
		s.inGame = "Zezima";
		assertEquals("Zezima", sentPlayer());
	}

	@Test
	public void onWithNullLocalPlayerSendsNothing() throws Exception
	{
		s.useInGame = true;
		assertNull(PixelPetPalsPlugin.readName(null));
		assertNull(PixelPetPalsPlugin.readName(player(null)));
		assertNull(PixelPetPalsPlugin.readName(player("  ")));
		s.inGame = PixelPetPalsPlugin.readName(null);
		assertNull(EventSender.activePlayer(s));
		sender.queueLogin(s);
		sender.queueLevelUp(s, "Mining", 50);
		assertFalse(sender.tick(s, wc));
		assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(0, server.getRequestCount());
	}
}
