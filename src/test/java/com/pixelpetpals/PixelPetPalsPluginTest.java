package com.pixelpetpals;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PixelPetPalsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PixelPetPalsPlugin.class);
		RuneLite.main(args);
	}
}
