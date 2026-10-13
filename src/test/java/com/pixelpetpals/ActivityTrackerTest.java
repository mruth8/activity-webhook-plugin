package com.pixelpetpals;

import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityTrackerTest
{
	@Test
	public void gatheringSkillsFromXpWhileAnimating()
	{
		for (Skill s : new Skill[]{Skill.WOODCUTTING, Skill.MINING, Skill.FISHING})
		{
			ActivityTracker t = new ActivityTracker();
			t.reset(0);
			t.onStat(s, 1000, 40, 1);   // baseline (login burst)
			assertEquals("idle", t.tick(2, true, null, false).getCategory());
			t.onStat(s, 1035, 40, 5);
			PetEvent.Activity a = t.tick(6, true, null, false);
			assertEquals("skilling", a.getCategory());
			assertEquals(s.getName(), a.getSkill());
			assertNull(a.getTarget());
		}
	}

	@Test
	public void nonGatheringXpIsNotSkilling()
	{
		ActivityTracker t = new ActivityTracker();
		t.reset(0);
		t.onStat(Skill.COOKING, 0, 1, 1);
		t.onStat(Skill.COOKING, 100, 2, 2);
		assertEquals("idle", t.tick(3, true, null, false).getCategory());
	}

	@Test
	public void bankingBeatsCombatBeatsSkilling()
	{
		ActivityTracker t = new ActivityTracker();
		t.reset(0);
		t.onStat(Skill.MINING, 0, 1, 1);
		t.onStat(Skill.MINING, 10, 1, 2);
		t.setBankOpen(true);
		assertEquals("banking", t.tick(3, true, "Goblin", true).getCategory());
		t.setBankOpen(false);
		PetEvent.Activity c = t.tick(4, true, "Goblin", false);
		assertEquals("combat", c.getCategory());
		assertNull("NPC name only when opted in", c.getTarget());
		assertEquals("Goblin", t.tick(5, true, "Goblin", true).getTarget());
		assertEquals("skilling", t.tick(6, true, null, false).getCategory());
	}

	@Test
	public void stickyThenIdleAfterIdleTicks()
	{
		ActivityTracker t = new ActivityTracker();
		t.reset(0);
		assertEquals("combat", t.tick(10, true, "Cow", false).getCategory());
		assertEquals("combat", t.tick(10 + ActivityTracker.IDLE_TICKS - 1, false, null, false).getCategory());
		assertEquals("idle", t.tick(10 + ActivityTracker.IDLE_TICKS, false, null, false).getCategory());
	}

	@Test
	public void levelUpOnlyAfterBaseline()
	{
		ActivityTracker t = new ActivityTracker();
		t.reset(0);
		assertEquals("login burst is not a level-up", -1, t.onStat(Skill.FISHING, 5000, 30, 1));
		assertEquals(-1, t.onStat(Skill.FISHING, 5100, 30, 2));
		assertEquals(31, t.onStat(Skill.FISHING, 5400, 31, 3));
		t.reset(4);
		assertEquals("after hop/login reset, first value is a baseline again", -1, t.onStat(Skill.FISHING, 9000, 40, 5));
	}
}
