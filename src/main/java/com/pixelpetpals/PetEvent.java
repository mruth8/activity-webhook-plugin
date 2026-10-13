package com.pixelpetpals;

import java.util.Map;
import java.util.Objects;
import lombok.Value;

/**
 * Event format v1 (schema/event-v1.schema.json). Null fields are omitted by Gson.
 * Only these fields exist: v, player, ts, type, activity{category, skill?, target?}, detail.
 * activity.region is never set (no location is sent).
 */
@Value
public class PetEvent
{
	int v = 1;
	String player;
	String ts;
	String type;
	Activity activity;
	Map<String, Object> detail;

	@Value
	public static class Activity
	{
		String category;
		String skill;
		String target;

		/** Identity used for "send on change". */
		String key()
		{
			return category + "|" + Objects.toString(skill, "") + "|" + Objects.toString(target, "");
		}
	}
}
