package com.activitywebhook;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ActivityWebhookPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ActivityWebhookPlugin.class);
		RuneLite.main(args);
	}
}
