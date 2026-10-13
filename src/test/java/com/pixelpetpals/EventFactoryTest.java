package com.pixelpetpals;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventFactoryTest
{
	private static final Set<String> TOP = new HashSet<>(Arrays.asList("v", "player", "ts", "type", "activity", "detail"));
	private static final Set<String> ACT = new HashSet<>(Arrays.asList("category", "skill", "target", "region"));
	private static final Set<String> TYPES = new HashSet<>(Arrays.asList("activity", "level_up", "loot", "death", "pet_drop", "quest", "login", "logout", "idle", "heartbeat"));
	private static final Set<String> CATS = new HashSet<>(Arrays.asList("skilling", "combat", "bossing", "banking", "traveling", "minigame", "idle"));

	private final EventFactory f = new EventFactory(new TestSupport.MutableClock(1_760_000_000_123L));
	private final Gson gson = new Gson();

	/** Mirrors schema/event-v1.schema.json (and shared/validate.js in the osrs-companion repo). */
	static void assertValidV1(JsonObject o)
	{
		for (String k : o.keySet())
		{
			assertTrue("unknown field " + k, TOP.contains(k));
		}
		assertEquals(1, o.get("v").getAsInt());
		String p = o.get("player").getAsString();
		assertTrue(p.length() >= 1 && p.length() <= 64);
		assertTrue(o.get("ts").getAsString().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"));
		assertTrue(TYPES.contains(o.get("type").getAsString()));
		if (o.has("activity"))
		{
			JsonObject a = o.getAsJsonObject("activity");
			for (String k : a.keySet())
			{
				assertTrue("unknown activity." + k, ACT.contains(k));
			}
			assertTrue(CATS.contains(a.get("category").getAsString()));
			assertFalse("region is never sent", a.has("region"));
		}
		if (o.has("detail"))
		{
			assertTrue(o.get("detail").isJsonObject());
		}
	}

	private JsonObject json(PetEvent e)
	{
		return new JsonParser().parse(gson.toJson(e)).getAsJsonObject();
	}

	@Test
	public void skillingEventHasExactlyTheV1Fields()
	{
		JsonObject o = json(f.state("Matthew", new PetEvent.Activity("skilling", "Woodcutting", null)));
		assertValidV1(o);
		assertEquals("{\"v\":1,\"player\":\"Matthew\",\"ts\":\"2025-10-09T08:53:20Z\",\"type\":\"activity\",\"activity\":{\"category\":\"skilling\",\"skill\":\"Woodcutting\"}}",
			gson.toJson(f.state("Matthew", new PetEvent.Activity("skilling", "Woodcutting", null))));
	}

	@Test
	public void idleAndHeartbeatAndBankingAndCombat()
	{
		JsonObject idle = json(f.state("M", ActivityTracker.IDLE));
		assertValidV1(idle);
		assertEquals("idle", idle.get("type").getAsString());
		assertEquals("idle", idle.getAsJsonObject("activity").get("category").getAsString());

		JsonObject hb = json(f.heartbeat("M", new PetEvent.Activity("banking", null, null)));
		assertValidV1(hb);
		assertEquals("heartbeat", hb.get("type").getAsString());

		JsonObject combat = json(f.state("M", new PetEvent.Activity("combat", null, null)));
		assertValidV1(combat);
		assertEquals(1, combat.getAsJsonObject("activity").keySet().size());

		JsonObject npc = json(f.state("M", new PetEvent.Activity("combat", null, "Hill Giant")));
		assertValidV1(npc);
		assertEquals("Hill Giant", npc.getAsJsonObject("activity").get("target").getAsString());
	}

	@Test
	public void levelUpLoginLogout()
	{
		JsonObject lv = json(f.levelUp("M", "Mining", 61));
		assertValidV1(lv);
		assertEquals("level_up", lv.get("type").getAsString());
		assertEquals("Mining", lv.getAsJsonObject("detail").get("skill").getAsString());
		assertEquals(61, lv.getAsJsonObject("detail").get("level").getAsInt());
		assertFalse(lv.has("activity"));
		assertValidV1(json(f.login("M")));
		assertValidV1(json(f.logout("M")));
	}

	@Test
	public void playerIsTrimmedClippedOrNull()
	{
		assertNull(EventFactory.cleanPlayer(null));
		assertNull(EventFactory.cleanPlayer("   "));
		assertEquals("Matt", EventFactory.cleanPlayer("  Matt "));
		assertEquals(64, EventFactory.cleanPlayer(new String(new char[100]).replace('\0', 'x')).length());
	}
}
