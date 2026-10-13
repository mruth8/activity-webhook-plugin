package com.activitywebhook;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Derives the local player's current activity from local-only signals, tick by tick. Pure: no Client access.
 * Priority: banking (bank open) > combat (interacting with an NPC) > skilling (recent Woodcutting/Mining/Fishing XP
 * while animating) > idle (nothing for IDLE_TICKS). Between signals the last activity is kept (sticky).
 */
public class ActivityTracker
{
	static final int SKILL_WINDOW_TICKS = 10;   // ~6 s: XP drops for WC/Mining/Fishing arrive every few ticks
	static final int IDLE_TICKS = 50;           // ~30 s of nothing => idle

	public static final WebhookEvent.Activity IDLE = new WebhookEvent.Activity("idle", null, null);

	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private Skill lastSkill;
	private int lastSkillTick = Integer.MIN_VALUE / 2;
	private int lastBusyTick;
	private boolean bankOpen;
	private WebhookEvent.Activity current = IDLE;

	/** Forget baselines (login, world hop) so the initial StatChanged burst isn't seen as XP gains or level-ups. */
	public void reset(int tick)
	{
		xp.clear();
		levels.clear();
		lastSkill = null;
		lastSkillTick = Integer.MIN_VALUE / 2;
		lastBusyTick = tick;
		bankOpen = false;
		current = IDLE;
	}

	/**
	 * Feed a StatChanged for the local player. Returns the new level if this is a real level-up (an increase over a
	 * level already seen since the last reset), else -1.
	 */
	public int onStat(Skill skill, int newXp, int realLevel, int tick)
	{
		Integer oldXp = xp.put(skill, newXp);
		if (oldXp != null && newXp > oldXp && isGatheringSkill(skill))
		{
			lastSkill = skill;
			lastSkillTick = tick;
		}
		Integer oldLevel = levels.put(skill, realLevel);
		return oldLevel != null && realLevel > oldLevel ? realLevel : -1;
	}

	public void setBankOpen(boolean open)
	{
		bankOpen = open;
	}

	static boolean isGatheringSkill(Skill s)
	{
		return s == Skill.WOODCUTTING || s == Skill.MINING || s == Skill.FISHING;
	}

	/**
	 * @param animating local player's animation != -1
	 * @param npcTarget name of the NPC the local player is interacting with, or null (players are never passed in)
	 */
	public WebhookEvent.Activity tick(int tick, boolean animating, String npcTarget, boolean includeNpcName)
	{
		WebhookEvent.Activity next = null;
		if (bankOpen)
		{
			next = new WebhookEvent.Activity("banking", null, null);
		}
		else if (npcTarget != null)
		{
			next = new WebhookEvent.Activity("combat", null, includeNpcName && !npcTarget.isEmpty() ? npcTarget : null);
		}
		else if (lastSkill != null && tick - lastSkillTick <= SKILL_WINDOW_TICKS && (animating || tick - lastSkillTick <= 2))
		{
			next = new WebhookEvent.Activity("skilling", lastSkill.getName(), null);
		}
		if (next != null || animating)
		{
			lastBusyTick = tick;
		}
		if (next != null)
		{
			current = next;
		}
		else if (tick - lastBusyTick >= IDLE_TICKS)
		{
			current = IDLE;
		}
		return current;
	}

	public WebhookEvent.Activity current()
	{
		return current;
	}
}
